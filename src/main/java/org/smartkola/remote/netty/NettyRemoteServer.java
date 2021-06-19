package org.smartkola.remote.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartkola.remote.common.Pair;
import org.smartkola.remote.common.RemoteHelper;
import org.smartkola.remote.common.RemotingUtil;
import org.smartkola.remote.common.TlsMode;
import org.smartkola.remote.errors.RemoteException;
import org.smartkola.remote.protocol.RemoteCmd;
import org.smartkola.remote.protocol.SerializeType;
import org.smartkola.remote.ChannelEventListener;
import org.smartkola.remote.InvokeCallback;
import org.smartkola.remote.RPCHook;
import org.smartkola.remote.RemoteServer;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.errors.RemoteTooMuchRequestException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class NettyRemoteServer extends NettyRemoteAbstract implements RemoteServer {

  private static final Logger log = LoggerFactory.getLogger(NettyRemoteServer.class);
  private final ServerBootstrap serverBootstrap;
  private final EventLoopGroup eventLoopGroupSelector;
  private final EventLoopGroup eventLoopGroupBoss;
  private final NettyServerConfig serverCfg;

  private final ExecutorService publicExecutor;
  private final ChannelEventListener channelEventListener;

  private final Timer timer = new Timer("ServerHouseKeepingService", true);
  private DefaultEventExecutorGroup defaultEventExecutorGroup;

  private int port = 0;

  private static final String HANDSHAKE_HANDLER_NAME = "handshakeHandler";
  private static final String TLS_HANDLER_NAME = "sslHandler";
  private static final String FILE_REGION_ENCODER_NAME = "fileRegionEncoder";

  // sharable handlers
  private HandshakeHandler handshakeHandler;
  private RemoteCmdEncoder encoder;
  private NettyConnectManageHandler connectionManageHandler;
  private NettyServerHandler serverHandler;

  private final SerializeType serializeType;

  private final Function<String, ThreadFactory> tf =
      fmtString ->
          new ThreadFactory() {
            private final AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
              return new Thread(r, String.format(fmtString, threadIndex.getAndIncrement()));
            }
          };

  public NettyRemoteServer(final NettyServerConfig serverCfg) {
    this(serverCfg, null);
  }

  public NettyRemoteServer(
      final NettyServerConfig serverCfg, final ChannelEventListener channelEventListener) {
    super(serverCfg.getServerOnewaySemaphoreValue(), serverCfg.getServerAsyncSemaphoreValue());

    this.serializeType = serverCfg.getSerializeType();
    this.serverCfg = serverCfg;
    this.channelEventListener = channelEventListener;
    this.serverBootstrap = new ServerBootstrap();

    int publicThreadNums = serverCfg.getServerCallbackExecutorThreads();

    if (serializeType == null)
      throw new IllegalArgumentException("Illegal serializeType require not null");

    if (publicThreadNums <= 0)
      throw new IllegalArgumentException(
          String.format("Illegal publicThreadNums: %d, must be gt zero", publicThreadNums));

    this.publicExecutor =
        Executors.newFixedThreadPool(publicThreadNums, tf.apply("NettyServerPublicExecutor_%d"));

    if (useEpoll()) {
      this.eventLoopGroupBoss = new EpollEventLoopGroup(1, tf.apply("NettyEPOLLBoss_%d"));
      this.eventLoopGroupSelector =
          new EpollEventLoopGroup(
              serverCfg.getServerSelectorThreads(),
              tf.apply("NettyServerEPOLLSelector_%d_" + serverCfg.getServerSelectorThreads()));
    } else {
      this.eventLoopGroupBoss = new NioEventLoopGroup(1, tf.apply("NettyNIOBoss_%d"));
      this.eventLoopGroupSelector =
          new NioEventLoopGroup(
              serverCfg.getServerSelectorThreads(),
              tf.apply("NettyServerEPOLLSelector_%d_" + serverCfg.getServerSelectorThreads()));
    }

    loadSslContext();
  }

  public void loadSslContext() {
    TlsMode tlsMode = TlsSystemConfig.tlsMode;
    log.info("Server is running in TLS {} mode", tlsMode.getName());
    if (tlsMode != TlsMode.DISABLED) {
      try {
        sslContext = TlsHelper.buildSslContext(false);
        log.info("SSLContext created for server");
      } catch (CertificateException | IOException e) {
        log.error("Failed to create SSLContext for server", e);
      }
    }
  }

  private boolean useEpoll() {
    return RemotingUtil.isLinuxPlatform()
        && serverCfg.isUseEpollNativeSelector()
        && Epoll.isAvailable();
  }

  @Override
  public void start() {
    this.defaultEventExecutorGroup =
        new DefaultEventExecutorGroup(
            serverCfg.getServerWorkerThreads(), tf.apply("NettyServerCodecThread_%d"));

    prepareSharableHandlers();

    ServerBootstrap childHandler =
        this.serverBootstrap
            .group(this.eventLoopGroupBoss, this.eventLoopGroupSelector)
            .channel(useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .option(ChannelOption.SO_REUSEADDR, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_SNDBUF, serverCfg.getServerSocketSndBufSize())
            .childOption(ChannelOption.SO_RCVBUF, serverCfg.getServerSocketRcvBufSize())
            .localAddress(new InetSocketAddress(this.serverCfg.getListenPort()))
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  public void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(
                            defaultEventExecutorGroup, HANDSHAKE_HANDLER_NAME, handshakeHandler)
                        .addLast(
                            defaultEventExecutorGroup,
                            encoder,
                            new RemoteCmdDecoder(),
                            new IdleStateHandler(
                                0, 0, serverCfg.getServerChannelMaxIdleTimeSeconds()),
                            connectionManageHandler,
                            serverHandler);
                  }
                });

    if (serverCfg.isServerPooledByteBufAllocatorEnable()) {
      childHandler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }

    try {
      ChannelFuture sync = this.serverBootstrap.bind().sync();
      InetSocketAddress addr = (InetSocketAddress) sync.channel().localAddress();
      this.port = addr.getPort();
    } catch (InterruptedException e) {
      throw new RuntimeException("this.serverBootstrap.bind().sync() InterruptedException", e);
    }

    if (this.channelEventListener != null) {
      this.nettyEventExecutor.start();
    }

    this.timer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            try {
              NettyRemoteServer.this.scanResponseTable();
            } catch (Throwable e) {
              log.error("scanResponseTable exception", e);
            }
          }
        },
        1000 * 3,
        1000);

    log.info("TCP server started, port:[{}]", this.port);
  }

  @Override
  public void shutdown() {
    try {
      this.timer.cancel();
      this.eventLoopGroupBoss.shutdownGracefully();
      this.eventLoopGroupSelector.shutdownGracefully();
      this.nettyEventExecutor.shutdown();
      if (this.defaultEventExecutorGroup != null) {
        this.defaultEventExecutorGroup.shutdownGracefully();
      }
    } catch (Exception e) {
      log.error("NettyRemotingServer shutdown exception, ", e);
    }

    if (this.publicExecutor != null) {
      try {
        this.publicExecutor.shutdown();
      } catch (Exception e) {
        log.error("NettyRemotingServer shutdown exception, ", e);
      }
    }
  }

  @Override
  public void addRPCHook(RPCHook rpcHook) {
    if (rpcHook != null && !rpcHooks.contains(rpcHook)) {
      rpcHooks.add(rpcHook);
    }
  }

  @Override
  public void registerHandler(
      int requestCode, Handler processor, ExecutorService executor) {
    ExecutorService executorThis = executor;
    if (null == executor) {
      executorThis = this.publicExecutor;
    }

    Pair<Handler, ExecutorService> pair = new Pair<>(processor, executorThis);
    this.processorTable.put(requestCode, pair);
  }

  @Override
  public void registerDefaultHandler(Handler processor, ExecutorService executor) {
    this.defaultRequestProcessor = new Pair<>(processor, executor);
  }

  @Override
  public int localListenPort() {
    return this.port;
  }

  @Override
  public Pair<Handler, ExecutorService> getProcessorPair(int requestCode) {
    return processorTable.get(requestCode);
  }

  @Override
  public RemoteCmd invokeSync(
      final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteException {
    return this.syncCall(channel, request, timeoutMillis);
  }

  @Override
  public void invokeAsync(
      Channel channel, RemoteCmd request, long timeoutMillis, InvokeCallback invokeCallback)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
      RemoteSendRequestException {
    this.asyncCall(channel, request, timeoutMillis, invokeCallback);
  }

  @Override
  public void invokeOneway(Channel channel, RemoteCmd request, long timeoutMillis)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
      RemoteSendRequestException {
    this.onewayCall(channel, request, timeoutMillis);
  }

  @Override
  public ChannelEventListener getChannelEventListener() {
    return channelEventListener;
  }

  @Override
  public ExecutorService getCallbackExecutor() {
    return this.publicExecutor;
  }

  private void prepareSharableHandlers() {
    handshakeHandler = new HandshakeHandler(TlsSystemConfig.tlsMode);
    encoder = new RemoteCmdEncoder();
    connectionManageHandler = new NettyConnectManageHandler();
    serverHandler = new NettyServerHandler();
  }

  @ChannelHandler.Sharable
  class HandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final TlsMode tlsMode;

    private static final byte HANDSHAKE_MAGIC_CODE = 0x16;

    HandshakeHandler(TlsMode tlsMode) {
      this.tlsMode = tlsMode;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
      // mark the current position so that we can peek the first byte to determine if the content is
      // starting with
      // TLS handshake
      msg.markReaderIndex();

      byte b = msg.getByte(0);

      if (b == HANDSHAKE_MAGIC_CODE) {
        switch (tlsMode) {
          case DISABLED:
            ctx.close();
            log.warn(
                "Clients intend to establish an SSL connection while this server is running in SSL disabled mode");
            break;
          case PERMISSIVE:
          case ENFORCING:
            if (null != sslContext) {
              ctx.pipeline()
                  .addAfter(
                      defaultEventExecutorGroup,
                      HANDSHAKE_HANDLER_NAME,
                      TLS_HANDLER_NAME,
                      sslContext.newHandler(ctx.channel().alloc()))
                  .addAfter(
                      defaultEventExecutorGroup,
                      TLS_HANDLER_NAME,
                      FILE_REGION_ENCODER_NAME,
                      new FileRegionEncoder());
              log.info("Handlers prepended to channel pipeline to establish SSL connection");
            } else {
              ctx.close();
              log.error("Trying to establish an SSL connection but sslContext is null");
            }
            break;

          default:
            log.warn("Unknown TLS mode");
            break;
        }
      } else if (tlsMode == TlsMode.ENFORCING) {
        ctx.close();
        log.warn(
            "Clients intend to establish an insecure connection while this server is running in"
                + " SSL enforcing mode");
      }

      // reset the reader index so that handshake negotiation may proceed as normal.
      msg.resetReaderIndex();

      try {
        // Remove this handler
        ctx.pipeline().remove(this);
      } catch (NoSuchElementException e) {
        log.error("Error while removing HandshakeHandler", e);
      }

      // Hand over this message to the next .
      ctx.fireChannelRead(msg.retain());
    }
  }

  @ChannelHandler.Sharable
  class NettyServerHandler extends SimpleChannelInboundHandler<RemoteCmd> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RemoteCmd msg) {
      processMessageReceived(ctx, msg);
    }
  }

  @ChannelHandler.Sharable
  class NettyConnectManageHandler extends ChannelDuplexHandler {
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.info("NETTY SERVER PIPELINE: channelRegistered {}", remoteAddress);
      super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.info("NETTY SERVER PIPELINE: channelUnregistered, the channel[{}]", remoteAddress);
      super.channelUnregistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.info("NETTY SERVER PIPELINE: channelActive, the channel[{}]", remoteAddress);
      super.channelActive(ctx);

      if (NettyRemoteServer.this.channelEventListener != null) {
        NettyRemoteServer.this.putNettyEvent(
            new NettyEvent(NettyEventType.CONNECT, remoteAddress, ctx.channel()));
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.info("NETTY SERVER PIPELINE: channelInactive, the channel[{}]", remoteAddress);
      super.channelInactive(ctx);

      if (NettyRemoteServer.this.channelEventListener != null) {
        NettyRemoteServer.this.putNettyEvent(
            new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
      }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
      ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.warn("NETTY SERVER PIPELINE: exceptionCaught {}", remoteAddress);
      log.warn("NETTY SERVER PIPELINE: exceptionCaught exception.", cause);

      if (NettyRemoteServer.this.channelEventListener != null) {
        NettyRemoteServer.this.putNettyEvent(
            new NettyExceptionEvent(NettyEventType.EXCEPTION, remoteAddress, ctx.channel(), cause));
      }

      RemotingUtil.closeChannel(ctx.channel());
    }
  }
}

package org.smartkola.remote.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartkola.remote.RemoteClient;
import org.smartkola.remote.common.RemoteHelper;
import org.smartkola.remote.common.RemotingUtil;
import org.smartkola.remote.errors.*;
import org.smartkola.remote.ChannelEventListener;
import org.smartkola.remote.InvokeCallback;
import org.smartkola.remote.RPCHook;
import org.smartkola.remote.common.Pair;
import org.smartkola.remote.protocol.RemoteCmd;

import java.io.IOException;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public class NettyRemoteClient extends NettyRemoteAbstract implements RemoteClient {

  private static final Logger log = LoggerFactory.getLogger(NettyRemoteClient.class);

  private static final long LOCK_TIMEOUT_MILLIS = 3000;

  private final NettyClientConfig nettyClientConfig;

  private final Bootstrap bootstrap = new Bootstrap();

  private final EventLoopGroup eventLoopGroupWorker;

  private final Lock channelTableLock = new ReentrantLock();

  private final ConcurrentMap<String, ChannelWrapper> channelTables = new ConcurrentHashMap<>();

  private final Timer timer = new Timer("ClientHouseKeepingService", true);

  private final AtomicReference<List<String>> namesrvAddrList = new AtomicReference<>();

  private final AtomicReference<String> namesrvAddrChoosed = new AtomicReference<>();

  private final AtomicInteger namesrvIndex = new AtomicInteger(initValueIndex());

  private final Lock lockNamesrvChannel = new ReentrantLock();

  private final ExecutorService publicExecutor;

  private ExecutorService callbackExecutor;

  private final ChannelEventListener channelEventListener;

  private DefaultEventExecutorGroup defaultEventExecutorGroup;

  private Bootstrap handler;

  private volatile Channel channel;

  public NettyRemoteClient(final NettyClientConfig clientCfg) {
    this(clientCfg, null);
  }

  public NettyRemoteClient(
      final NettyClientConfig clientCfg, final ChannelEventListener channelEventListener) {

    super(clientCfg.getClientOnewaySemaphoreValue(), clientCfg.getClientAsyncSemaphoreValue());

    this.nettyClientConfig = clientCfg;
    this.channelEventListener = channelEventListener;

    int publicThreadNums;
    if ((publicThreadNums = clientCfg.getClientCallbackExecutorThreads()) <= 0)
      throw new IllegalArgumentException("publicThreadNums must be gt zero. ");

    Function<String, ThreadFactory> tf =
        fmtString ->
            new ThreadFactory() {
              private final AtomicInteger threadIndex = new AtomicInteger(0);

              @Override
              public Thread newThread(Runnable runnable) {
                return new Thread(runnable, String.format(fmtString, threadIndex));
              }
            };
    this.publicExecutor =
        Executors.newFixedThreadPool(publicThreadNums, tf.apply("NettyClientPublicExecutor_%s"));

    this.eventLoopGroupWorker = new NioEventLoopGroup(1, tf.apply("NettyClientSelector_%d"));

    if (clientCfg.isUseTLS()) {
      try {
        sslContext = TlsHelper.buildSslContext(true);
        log.info("SSL enabled for client");
      } catch (Exception e) {
        if (e instanceof IOException) {
          log.error("Failed to create SSLContext", e);
        } else if (e instanceof CertificateException) {
          log.error("Failed to create SSLContext", e);
          throw new RuntimeException("Failed to create SSLContext", e);
        }
      }
    }

    this.defaultEventExecutorGroup =
        new DefaultEventExecutorGroup(
            clientCfg.getClientWorkerThreads(), tf.apply("NettyClientWorkerThread_%d"));

    handler =
        this.bootstrap
            .group(this.eventLoopGroupWorker)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, clientCfg.getConnectTimeoutMillis())
            .option(ChannelOption.SO_SNDBUF, clientCfg.getClientSocketSndBufSize())
            .option(ChannelOption.SO_RCVBUF, clientCfg.getClientSocketRcvBufSize())
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  public void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    if (clientCfg.isUseTLS() && sslContext != null) {
                      pipeline.addFirst(
                          defaultEventExecutorGroup,
                          "sslHandler",
                          sslContext.newHandler(ch.alloc()));
                      log.info("Prepend SSL handler");
                    }

                    pipeline.addLast(
                        defaultEventExecutorGroup,
                        new CmdEncoder(),
                        new CmdDecoder(),
                        new IdleStateHandler(0, 0, clientCfg.getClientChannelMaxIdleTimeSeconds()),
                        new NettyConnectManageHandler(),
                        new ReconnectHandler(NettyRemoteClient.this),
                        new NettyClientHandler());
                  }
                });
  }

  private static int initValueIndex() {
    Random r = new Random();
    return Math.abs(r.nextInt() % 999) % 999;
  }

  @Override
  public void start() throws RemoteException {
    synchronized (this) {
      try {
        connect();
        this.timer.scheduleAtFixedRate(
            new TimerTask() {
              @Override
              public void run() {
                try {
                  NettyRemoteClient.this.scanResponseTable();
                } catch (Throwable e) {
                  log.error("scanResponseTable exception", e);
                }
              }
            },
            1000 * 3,
            1000);

        if (this.channelEventListener != null) this.nettyEventExecutor.start();

      } catch (Exception e) {
        e.printStackTrace();
        throw new RemoteException(e.getMessage(), e);
      }
    }
  }

  public void connect() throws RemoteException {
    ChannelFuture future =
        handler.connect(nettyClientConfig.getHost(), nettyClientConfig.getPort());

    future.addListener(
        f -> {
          if (!f.isSuccess()) {
            if (f.cause() != null) f.cause().printStackTrace();
            Channel newChannel = future.channel();
            Channel oldChannel = NettyRemoteClient.this.channel;
            if (oldChannel != null) {
              try {
                log.info("Remove old channel {} ", oldChannel);
                oldChannel.close();
              } catch (Exception e) {
                e.printStackTrace();
              } finally {
                NettyRemoteClient.this.channelTables.remove(
                    RemoteHelper.parseChannelRemoteAddr(oldChannel));
              }
            } else {
              NettyRemoteClient.this.channel = newChannel;
            }
            future.channel().pipeline().fireChannelInactive();
          } else {
            NettyRemoteClient.this.channel = future.channel();
          }
        });
  }

  public void disconnect() throws RemoteException {
    if (this.channel != null) {
      if (channel.isActive()) {
        try {
          channel.close();
        } catch (Exception e) {
          log.error("Close channel error {} ", e.getMessage());
          e.printStackTrace();
          throw new RemoteException(e.getMessage(), e);
        } finally {
          this.channelTables.remove(RemoteHelper.parseChannelRemoteAddr(this.channel));
        }
      } else {
        log.error("Not channel connected server");
      }
    }
  }

  @Override
  public void shutdown() {
    try {
      this.timer.cancel();

      for (ChannelWrapper cw : this.channelTables.values()) {
        this.closeChannel(null, cw.getChannel());
      }

      this.channelTables.clear();
      this.eventLoopGroupWorker.shutdownGracefully();
      this.nettyEventExecutor.shutdown();

      if (this.defaultEventExecutorGroup != null)
        this.defaultEventExecutorGroup.shutdownGracefully();
    } catch (Exception e) {
      log.error("NettyRemotingClient shutdown exception, ", e);
    }

    if (this.publicExecutor != null) {
      try {
        this.publicExecutor.shutdown();
      } catch (Exception e) {
        log.error("NettyRemotingServer shutdown exception, ", e);
      }
    }
  }

  public void closeChannel(final String addr, final Channel channel) {
    if (null == channel) return;

    final String addrRemote = null == addr ? RemoteHelper.parseChannelRemoteAddr(channel) : addr;

    try {
      if (this.channelTableLock.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        try {
          boolean removeItemFromTable = true;
          final ChannelWrapper prevCW = this.channelTables.get(addrRemote);

          log.info(
              "closeChannel: begin close the channel[{}] Found: {}", addrRemote, prevCW != null);

          if (null == prevCW) {
            log.info(
                "closeChannel: the channel[{}] has been removed from the channel table before",
                addrRemote);
            removeItemFromTable = false;
          } else if (prevCW.getChannel() != channel) {
            log.info(
                "closeChannel: the channel[{}] has been closed before, and has been created again,"
                    + " nothing to do.",
                addrRemote);
            removeItemFromTable = false;
          }

          if (removeItemFromTable) {
            this.channelTables.remove(addrRemote);
            log.info("closeChannel: the channel[{}] was removed from channel table", addrRemote);
          }

          RemotingUtil.closeChannel(channel);
        } catch (Exception e) {
          log.error("closeChannel: close the channel exception", e);
        } finally {
          this.channelTableLock.unlock();
        }
      } else {
        log.warn("closeChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
      }
    } catch (InterruptedException e) {
      log.error("closeChannel exception", e);
    }
  }

  @Override
  public void addRPCHook(RPCHook rpcHook) {
    if (rpcHook != null && !rpcHooks.contains(rpcHook)) {
      rpcHooks.add(rpcHook);
    }
  }

  public void closeChannel(final Channel channel) {
    if (null == channel) return;

    try {
      if (this.channelTableLock.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        try {
          boolean removeItemFromTable = true;
          ChannelWrapper prevCW = null;
          String addrRemote = null;
          for (Map.Entry<String, ChannelWrapper> entry : channelTables.entrySet()) {
            String key = entry.getKey();
            ChannelWrapper prev = entry.getValue();
            if (prev.getChannel() != null) {
              if (prev.getChannel() == channel) {
                prevCW = prev;
                addrRemote = key;
                break;
              }
            }
          }

          if (null == prevCW) {
            log.info(
                "eventCloseChannel: the channel[{}] has been removed from the channel table before",
                addrRemote);
            removeItemFromTable = false;
          }

          if (removeItemFromTable) {
            this.channelTables.remove(addrRemote);
            log.info("closeChannel: the channel[{}] was removed from channel table", addrRemote);
            RemotingUtil.closeChannel(channel);
          }
        } catch (Exception e) {
          log.error("closeChannel: close the channel exception", e);
        } finally {
          this.channelTableLock.unlock();
        }
      } else {
        log.warn("closeChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
      }
    } catch (InterruptedException e) {
      log.error("closeChannel exception", e);
    }
  }

  @Override
  public void updateNameServerAddressList(List<String> addrs) {
    List<String> old = this.namesrvAddrList.get();
    boolean update = false;

    if (!addrs.isEmpty()) {
      if (null == old) {
        update = true;
      } else if (addrs.size() != old.size()) {
        update = true;
      } else {
        for (int i = 0; i < addrs.size() && !update; i++) {
          if (!old.contains(addrs.get(i))) {
            update = true;
          }
        }
      }

      if (update) {
        Collections.shuffle(addrs);
        log.info("name server address updated. NEW : {} , OLD: {}", addrs, old);
        this.namesrvAddrList.set(addrs);

        if (!addrs.contains(this.namesrvAddrChoosed.get())) {
          this.namesrvAddrChoosed.set(null);
        }
      }
    }
  }

  @Override
  public RemoteCmd syncCall(String addr, final RemoteCmd request, long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteSendRequestException,
          RemoteTimeoutException {
    long beginStartTime = System.currentTimeMillis();
    final Channel channel = this.getAndCreateChannel(addr);
    if (channel != null && channel.isActive()) {
      try {
        runBeforeRpcHooks(addr, request);

        long costTime = System.currentTimeMillis() - beginStartTime;
        if (timeoutMillis < costTime) throw new RemoteTimeoutException("syncCall call timeout");

        RemoteCmd response = this.syncCall(channel, request, timeoutMillis - costTime);
        runAfterRpcHooks(RemoteHelper.parseChannelRemoteAddr(channel), request, response);
        return response;
      } catch (RemoteSendRequestException e) {
        log.warn("syncCall: send request exception, so close the channel[{}]", addr);
        this.closeChannel(addr, channel);
        throw e;
      } catch (RemoteTimeoutException e) {
        if (nettyClientConfig.isClientCloseSocketIfTimeout()) {
          this.closeChannel(addr, channel);
          log.warn("syncCall: close socket because of timeout, {}ms, {}", timeoutMillis, addr);
        }
        log.warn("syncCall: wait response timeout exception, the channel[{}]", addr);
        throw e;
      } catch (RemoteException e) {
        e.printStackTrace();
      }
    }
    this.closeChannel(addr, channel);
    throw new RemoteConnectException(addr);
  }

  public ConcurrentMap<String, ChannelWrapper> getChannelTables() {
    return channelTables;
  }

  private Channel getAndCreateChannel(final String addr)
      throws RemoteConnectException, InterruptedException {
    if (null == addr) {
      return getAndCreateNameserverChannel();
    }

    ChannelWrapper cw = this.channelTables.get(addr);
    if (cw != null && cw.isOK()) {
      return cw.getChannel();
    }

    return this.createChannel(addr);
  }

  private Channel getAndCreateNameserverChannel()
      throws RemoteConnectException, InterruptedException {
    String addr = this.namesrvAddrChoosed.get();
    if (addr != null) {
      ChannelWrapper cw = this.channelTables.get(addr);
      if (cw != null && cw.isOK()) {
        return cw.getChannel();
      }
    }

    final List<String> addrList = this.namesrvAddrList.get();
    if (this.lockNamesrvChannel.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
      try {
        addr = this.namesrvAddrChoosed.get();
        if (addr != null) {
          ChannelWrapper cw = this.channelTables.get(addr);
          if (cw != null && cw.isOK()) {
            return cw.getChannel();
          }
        }

        if (addrList != null && !addrList.isEmpty()) {
          for (int i = 0; i < addrList.size(); i++) {
            int index = this.namesrvIndex.incrementAndGet();
            index = Math.abs(index);
            index = index % addrList.size();
            String newAddr = addrList.get(index);

            this.namesrvAddrChoosed.set(newAddr);
            log.info(
                "new name server is chosen. OLD: {} , NEW: {}. namesrvIndex = {}",
                addr,
                newAddr,
                namesrvIndex);
            Channel channelNew = this.createChannel(newAddr);
            if (channelNew != null) {
              return channelNew;
            }
          }
          throw new RemoteConnectException(addrList.toString());
        }
      } finally {
        this.lockNamesrvChannel.unlock();
      }
    } else {
      log.warn(
          "getAndCreateNameserverChannel: try to lock name server, but timeout, {}ms",
          LOCK_TIMEOUT_MILLIS);
    }

    return null;
  }

  private Channel createChannel(final String addr) throws InterruptedException {
    ChannelWrapper cw = this.channelTables.get(addr);
    if (cw != null && cw.isOK()) {
      return cw.getChannel();
    }

    if (this.channelTableLock.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
      try {
        boolean createNewConnection;
        cw = this.channelTables.get(addr);
        if (cw != null) {

          if (cw.isOK()) {
            return cw.getChannel();
          } else if (!cw.getChannelFuture().isDone()) {
            createNewConnection = false;
          } else {
            this.channelTables.remove(addr);
            createNewConnection = true;
          }
        } else {
          createNewConnection = true;
        }

        if (createNewConnection) {
          ChannelFuture channelFuture =
              this.bootstrap.connect(RemoteHelper.string2SocketAddress(addr));
          log.info("createChannel: begin to connect remote host[{}] asynchronously", addr);
          cw = new ChannelWrapper(channelFuture);
          this.channelTables.put(addr, cw);
        }
      } catch (Exception e) {
        log.error("createChannel: create channel exception", e);
      } finally {
        this.channelTableLock.unlock();
      }
    } else {
      log.warn("createChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
    }

    if (cw != null) {
      ChannelFuture channelFuture = cw.getChannelFuture();
      if (channelFuture.awaitUninterruptibly(this.nettyClientConfig.getConnectTimeoutMillis())) {
        if (cw.isOK()) {
          log.info(
              "createChannel: connect remote host[{}] success, {}", addr, channelFuture.toString());
          return cw.getChannel();
        } else {
          log.warn(
              "createChannel: connect remote host["
                  + addr
                  + "] failed, "
                  + channelFuture.toString(),
              channelFuture.cause());
        }
      } else {
        log.warn(
            "createChannel: connect remote host[{}] timeout {}ms, {}",
            addr,
            this.nettyClientConfig.getConnectTimeoutMillis(),
            channelFuture.toString());
      }
    }

    return null;
  }

  @Override
  public void invokeAsync(
      String addr, RemoteCmd request, long timeoutMillis, InvokeCallback invokeCallback)
      throws InterruptedException, RemoteConnectException, RemoteTooMuchRequestException,
          RemoteTimeoutException, RemoteSendRequestException {
    long beginStartTime = System.currentTimeMillis();
    final Channel channel = this.getAndCreateChannel(addr);
    if (channel != null && channel.isActive()) {
      try {
        runBeforeRpcHooks(addr, request);
        long costTime = System.currentTimeMillis() - beginStartTime;
        if (timeoutMillis < costTime) {
          throw new RemoteTooMuchRequestException("invokeAsync call timeout");
        }
        this.asyncCall(channel, request, timeoutMillis - costTime, invokeCallback);
      } catch (RemoteSendRequestException e) {
        log.warn("invokeAsync: send request exception, so close the channel[{}]", addr);
        this.closeChannel(addr, channel);
        throw e;
      }
    } else {
      this.closeChannel(addr, channel);
      throw new RemoteConnectException(addr);
    }
  }

  @Override
  public void invokeOneway(String addr, RemoteCmd request, long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteTooMuchRequestException,
          RemoteTimeoutException, RemoteSendRequestException {
    final Channel channel = this.getAndCreateChannel(addr);
    if (channel != null && channel.isActive()) {
      try {
        runBeforeRpcHooks(addr, request);
        this.onewayCall(channel, request, timeoutMillis);
      } catch (RemoteSendRequestException e) {
        log.warn("invokeOneway: send request exception, so close the channel[{}]", addr);
        this.closeChannel(addr, channel);
        throw e;
      }
    } else {
      this.closeChannel(addr, channel);
      throw new RemoteConnectException(addr);
    }
  }

  @Override
  public void registerProcessor(
      int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
    ExecutorService executorThis = executor;
    if (null == executor) {
      executorThis = this.publicExecutor;
    }

    Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<>(processor, executorThis);
    this.processorTable.put(requestCode, pair);
  }

  @Override
  public boolean isChannelWritable(String addr) {
    ChannelWrapper cw = this.channelTables.get(addr);
    if (cw != null && cw.isOK()) {
      return cw.isWritable();
    }
    return true;
  }

  @Override
  public List<String> getNameServerAddressList() {
    return this.namesrvAddrList.get();
  }

  @Override
  public ChannelEventListener getChannelEventListener() {
    return channelEventListener;
  }

  @Override
  public ExecutorService getCallbackExecutor() {
    return callbackExecutor != null ? callbackExecutor : publicExecutor;
  }

  @Override
  public void setCallbackExecutor(final ExecutorService callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  static class ChannelWrapper {
    private final ChannelFuture channelFuture;

    public ChannelWrapper(ChannelFuture channelFuture) {
      this.channelFuture = channelFuture;
    }

    public boolean isOK() {
      return this.channelFuture.channel() != null && this.channelFuture.channel().isActive();
    }

    public boolean isWritable() {
      return this.channelFuture.channel().isWritable();
    }

    private Channel getChannel() {
      return this.channelFuture.channel();
    }

    public ChannelFuture getChannelFuture() {
      return channelFuture;
    }
  }

  class NettyClientHandler extends SimpleChannelInboundHandler<RemoteCmd> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RemoteCmd msg) throws Exception {
      processMessageReceived(ctx, msg);
    }
  }

  class NettyConnectManageHandler extends ChannelDuplexHandler {

    @Override
    public void connect(
        ChannelHandlerContext ctx,
        SocketAddress remoteAddress,
        SocketAddress localAddress,
        ChannelPromise promise)
        throws Exception {
      final String local =
          localAddress == null ? "UNKNOWN" : RemoteHelper.parseSocketAddressAddr(localAddress);
      final String remote =
          remoteAddress == null ? "UNKNOWN" : RemoteHelper.parseSocketAddressAddr(remoteAddress);
      log.info("NETTY CLIENT PIPELINE: CONNECT  {} => {}", local, remote);

      super.connect(ctx, remoteAddress, localAddress, promise);

      if (NettyRemoteClient.this.channelEventListener != null) {
        NettyRemoteClient.this.putNettyEvent(
            new NettyEvent(NettyEventType.CONNECT, remote, ctx.channel()));
      }
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.info("NETTY CLIENT PIPELINE: DISCONNECT {}", remoteAddress);
      closeChannel(ctx.channel());
      super.disconnect(ctx, promise);

      if (NettyRemoteClient.this.channelEventListener != null) {
        NettyRemoteClient.this.putNettyEvent(
            new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
      }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.info("NETTY CLIENT PIPELINE: CLOSE {}", remoteAddress);
      closeChannel(ctx.channel());
      super.close(ctx, promise);
      NettyRemoteClient.this.failFast(ctx.channel());
      if (NettyRemoteClient.this.channelEventListener != null) {
        NettyRemoteClient.this.putNettyEvent(
            new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
      }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

      /*if (evt instanceof IdleStateEvent) {
          IdleStateEvent event = (IdleStateEvent) evt;
          if (event.state().equals(IdleState.ALL_IDLE)) {
              final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
              log.warn("NETTY CLIENT PIPELINE: IDLE exception [{}]", remoteAddress);
              closeChannel(ctx.channel());
              if (NettyRemotingClient.this.channelEventListener != null) {
                  NettyRemotingClient.this
                          .putNettyEvent(new NettyEvent(NettyEventType.IDLE, remoteAddress, ctx.channel()));
              }
          }
      }*/

      ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      final String remoteAddress = RemoteHelper.parseChannelRemoteAddr(ctx.channel());
      log.warn("NETTY CLIENT PIPELINE: exceptionCaught {}", remoteAddress);
      log.warn("NETTY CLIENT PIPELINE: exceptionCaught exception.", cause);
      closeChannel(ctx.channel());
      if (NettyRemoteClient.this.channelEventListener != null) {
        NettyRemoteClient.this.putNettyEvent(
            new NettyExceptionEvent(NettyEventType.EXCEPTION, remoteAddress, ctx.channel(), cause));
      }
    }
  }
}

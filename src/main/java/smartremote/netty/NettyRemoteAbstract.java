package smartremote.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smartremote.ChannelEventListener;
import smartremote.RPCHook;
import smartremote.common.Pair;
import smartremote.common.RemoteHelper;
import smartremote.common.SemaphoreReleaseOnlyOnce;
import smartremote.common.ServiceThread;
import smartremote.errors.RemoteTimeoutException;
import smartremote.protocol.RemoteCmd;
import smartremote.InvokeCallback;
import smartremote.errors.RemoteSendRequestException;
import smartremote.errors.RemoteTooMuchRequestException;
import smartremote.protocol.ResponseCode;

import java.net.SocketAddress;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;

import static smartremote.common.RemoteHelper.parseChannelRemoteAddr;
import static smartremote.common.RemoteHelper.parseSocketAddressAddr;
import static smartremote.protocol.RemoteCmdType.REQUEST_COMMAND;
import static smartremote.protocol.RemoteCmdType.RESPONSE_COMMAND;

public abstract class NettyRemoteAbstract {

  private static final Logger log = LoggerFactory.getLogger(NettyRemoteAbstract.class);

  /**
   * Semaphore to limit maximum number of on-going one-way requests, which protects system memory
   * footprint.
   */
  protected final Semaphore semaphoreOneway;

  /**
   * Semaphore to limit maximum number of on-going asynchronous requests, which protects system
   * memory footprint.
   */
  protected final Semaphore semaphoreAsync;

  protected final ConcurrentMap<Integer, ResponseFuture> responseTable =
      new ConcurrentHashMap<>(256);

  /**
   * This container holds all processors per request code, aka, for each incoming request, we may
   * look up the responding processor in this map to handle the request.
   */
  protected final HashMap<Integer /* request code */, Pair<NettyRequestProcessor, ExecutorService>>
      processorTable = new HashMap<>(64);

  /** Executor to feed netty events to user defined {@link ChannelEventListener}. */
  protected final NettyEventExecutor nettyEventExecutor = new NettyEventExecutor();

  /**
   * The default request processor to use in case there is no exact match in {@link #processorTable}
   * per request code.
   */
  protected Pair<NettyRequestProcessor, ExecutorService> defaultRequestProcessor;

  /** SSL context via which to create {@link SslHandler}. */
  protected volatile SslContext sslContext;

  @Deprecated protected List<RPCHook> rpcHooks = new ArrayList<>();

  protected List<RPCHook> beforeRPCHooks = new ArrayList<>();

  protected List<RPCHook> afterRPCHooks = new ArrayList<>();

  /**
   * Constructor, specifying capacity of one-way and asynchronous semaphores.
   *
   * @param permitsOneway Number of permits for one-way requests.
   * @param permitsAsync Number of permits for asynchronous requests.
   */
  public NettyRemoteAbstract(final int permitsOneway, final int permitsAsync) {
    this.semaphoreOneway = new Semaphore(permitsOneway, true);
    this.semaphoreAsync = new Semaphore(permitsAsync, true);
  }

  public abstract ChannelEventListener getChannelEventListener();

  public void putNettyEvent(final NettyEvent event) {
    this.nettyEventExecutor.putNettyEvent(event);
  }

  public void processMessageReceived(ChannelHandlerContext ctx, RemoteCmd msg) {
    if (msg == null) return;

    if (msg.getType() == REQUEST_COMMAND) {
      processRequestCmd(ctx, msg);
    }
    if (msg.getType() == RESPONSE_COMMAND) {
      processResponseCmd(ctx, msg);
    }
  }

  protected void runBeforeRpcHooks(String addr, RemoteCmd request) {
    beforeRPCHooks.forEach(hook -> hook.runBeforeRequest(addr, request));
  }

  protected void runAfterRpcHooks(String addr, RemoteCmd request, RemoteCmd response) {
    afterRPCHooks.forEach(hook -> hook.runAfterResponse(addr, request, response));
  }

  public void processRequestCmd(final ChannelHandlerContext ctx, final RemoteCmd cmd) {

    final Pair<NettyRequestProcessor, ExecutorService> endpointProcessor =
        this.processorTable.get(cmd.getCode());

    final Pair<NettyRequestProcessor, ExecutorService> pair =
        null == endpointProcessor ? this.defaultRequestProcessor : endpointProcessor;

    final int opaque = cmd.getOpaque();

    if (pair == null) {
      String error = String.format("request type %d not supported", cmd.getCode());
      final RemoteCmd response =
          RemoteCmd.newResponse(ResponseCode.REQUEST_CODE_NOT_SUPPORTED, error);
      response.setOpaque(opaque);
      ctx.writeAndFlush(response);
      log.error(parseChannelRemoteAddr(ctx.channel()) + error);
      return;
    }

    if (pair.getLeft().rejectRequest()) {
      final RemoteCmd response =
          RemoteCmd.newResponse(
              ResponseCode.SYSTEM_BUSY,
              "[REJECTREQUEST]system busy, start flow control for a while");
      response.setOpaque(opaque);
      ctx.writeAndFlush(response);
      return;
    }

    Runnable runnable =
        () -> {
          try {
            runBeforeRpcHooks(parseChannelRemoteAddr(ctx.channel()), cmd);

            final RemoteResponseCallback callback =
                response -> {
                  runAfterRpcHooks(parseChannelRemoteAddr(ctx.channel()), cmd, response);
                  if (!cmd.isOnewayRPC()) {
                    if (response != null) {
                      response.setOpaque(opaque);
                      try {
                        ctx.writeAndFlush(response);
                      } catch (Throwable e) {
                        log.error("process request over, but response failed", e);
                        log.error(cmd.toString());
                        log.error(response.toString());
                      }
                    }
                  }
                };

            if (pair.getLeft() instanceof AsyncNettyRequestProcessor) {
              AsyncNettyRequestProcessor processor = (AsyncNettyRequestProcessor) pair.getLeft();
              processor.asyncProcessRequest(ctx, cmd, callback);
            } else {
              NettyRequestProcessor processor = pair.getLeft();
              RemoteCmd response = processor.processRequest(ctx, cmd);
              callback.call(response);
            }
          } catch (Throwable e) {
            log.error("process request exception", e);
            log.error(cmd.toString());

            if (!cmd.isOnewayRPC()) {
              final RemoteCmd response =
                  RemoteCmd.newResponse(
                      ResponseCode.SYSTEM_ERROR, RemoteHelper.exceptionSimpleDesc(e));
              response.setOpaque(opaque);
              ctx.writeAndFlush(response);
            }
          }
        };

    try {
      final RequestTask task = new RequestTask(runnable, ctx.channel(), cmd);
      pair.getRight().submit(task);
    } catch (RejectedExecutionException e) {

      log.warn(
          String.format(
              "%s, too many requests and system thread pool busy, RejectedExecutionException %s request code: %d",
              parseChannelRemoteAddr(ctx.channel()), pair.getRight(), cmd.getCode()));

      if (!cmd.isOnewayRPC()) {
        final RemoteCmd response =
            RemoteCmd.newResponse(
                ResponseCode.SYSTEM_BUSY, "[OVERLOAD] system busy, start flow control for a while");
        response.setOpaque(opaque);
        ctx.writeAndFlush(response);
      }
    }
  }

  public void processResponseCmd(ChannelHandlerContext ctx, RemoteCmd cmd) {
    final int opaque = cmd.getOpaque();
    final ResponseFuture future = responseTable.get(opaque);

    if (future != null) {
      future.setResponse(cmd);
      responseTable.remove(opaque);

      if (future.getCallback() != null) {
        executeInvokeCallback(future);
      } else {
        future.putResponse(cmd);
        future.release();
      }
    } else {
      log.warn(
          String.format(
              "receive response, but not matched any request, %s",
              parseChannelRemoteAddr(ctx.channel())));
      log.warn(cmd.toString());
    }
  }

  private void executeInvokeCallback(final ResponseFuture future) {
    boolean runInThisThread = false;
    ExecutorService executor = this.getCallbackExecutor();
    if (executor != null) {
      try {
        executor.submit(
            () -> {
              try {
                future.runInvokeCallback();
              } catch (Throwable e) {
                log.warn("execute callback in executor exception, and callback throw", e);
              } finally {
                future.release();
              }
            });
      } catch (Exception e) {
        runInThisThread = true;
        log.warn("execute callback in executor exception, maybe executor busy", e);
      }
    } else {
      runInThisThread = true;
    }

    if (runInThisThread) {
      try {
        future.runInvokeCallback();
      } catch (Throwable e) {
        log.warn("executeInvokeCallback Exception", e);
      } finally {
        future.release();
      }
    }
  }

  /**
   * This method specifies thread pool to use while invoking callback methods.
   *
   * @return Dedicated thread pool instance if specified; or null if the callback is supposed to be
   *     executed in the netty event-loop thread.
   */
  public abstract ExecutorService getCallbackExecutor();

  public void scanResponseTable() {
    final List<ResponseFuture> futures = new LinkedList<>();
    Iterator<Entry<Integer, ResponseFuture>> it = this.responseTable.entrySet().iterator();
    while (it.hasNext()) {
      Entry<Integer, ResponseFuture> next = it.next();
      ResponseFuture rep = next.getValue();

      if ((rep.getBeginTimestamp() + rep.getTimeoutMillis() + 1000) <= System.currentTimeMillis()) {
        rep.release();
        it.remove();
        futures.add(rep);
        log.warn("remove timeout request, " + rep);
      }
    }

    for (ResponseFuture rf : futures) {
      try {
        executeInvokeCallback(rf);
      } catch (Throwable e) {
        log.warn("scanResponseTable, operationComplete Exception", e);
      }
    }
  }

  public RemoteCmd invokeSyncImpl(
      final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteSendRequestException, RemoteTimeoutException {

    final int opaque = request.getOpaque();

    try {
      final ResponseFuture future = new ResponseFuture(channel, opaque, timeoutMillis, null, null);
      this.responseTable.put(opaque, future);
      final SocketAddress addr = channel.remoteAddress();
      channel
          .writeAndFlush(request)
          .addListener(
              (ChannelFutureListener)
                  f -> {
                    if (f.isSuccess()) {
                      future.setSendRequestOK(true);
                      return;
                    } else {
                      future.setSendRequestOK(false);
                    }

                    responseTable.remove(opaque);
                    future.setCause(f.cause());
                    future.putResponse(null);
                    log.warn("send a request command to channel <" + addr + "> failed.");
                  });

      RemoteCmd remoteCmd = future.waitResponse(timeoutMillis);

      if (null == remoteCmd) {
        if (future.isSendRequestOK()) {
          throw new RemoteTimeoutException(
              parseSocketAddressAddr(addr), timeoutMillis, future.getCause());
        } else {
          throw new RemoteSendRequestException(parseSocketAddressAddr(addr), future.getCause());
        }
      }

      return remoteCmd;
    } finally {
      this.responseTable.remove(opaque);
    }
  }

  public void invokeAsyncImpl(
      final Channel channel,
      final RemoteCmd request,
      final long timeoutMillis,
      final InvokeCallback invokeCallback)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
          RemoteSendRequestException {
    long beginStartTime = System.currentTimeMillis();
    final int opaque = request.getOpaque();
    boolean acquired = this.semaphoreAsync.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    if (acquired) {
      final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreAsync);
      long costTime = System.currentTimeMillis() - beginStartTime;
      if (timeoutMillis < costTime) {
        once.release();
        throw new RemoteTimeoutException("invokeAsyncImpl call timeout");
      }

      final ResponseFuture responseFuture =
          new ResponseFuture(channel, opaque, timeoutMillis - costTime, invokeCallback, once);
      this.responseTable.put(opaque, responseFuture);
      try {
        channel
            .writeAndFlush(request)
            .addListener(
                (ChannelFutureListener)
                    f -> {
                      if (f.isSuccess()) {
                        responseFuture.setSendRequestOK(true);
                        return;
                      }
                      requestFail(opaque);
                      log.warn(
                          "send a request command to channel <{}> failed.",
                          parseChannelRemoteAddr(channel));
                    });
      } catch (Exception e) {
        responseFuture.release();
        log.warn(
            "send a request command to channel <" + parseChannelRemoteAddr(channel) + "> Exception",
            e);
        throw new RemoteSendRequestException(parseChannelRemoteAddr(channel), e);
      }
    } else {
      if (timeoutMillis <= 0) {
        throw new RemoteTooMuchRequestException("invokeAsyncImpl invoke too fast");
      } else {
        String info =
            String.format(
                "invokeAsyncImpl tryAcquire semaphore timeout, %dms, waiting thread nums: %d"
                    + " semaphoreAsyncValue: %d",
                timeoutMillis,
                this.semaphoreAsync.getQueueLength(),
                this.semaphoreAsync.availablePermits());
        log.warn(info);
        throw new RemoteTimeoutException(info);
      }
    }
  }

  private void requestFail(final int opaque) {
    ResponseFuture responseFuture = responseTable.remove(opaque);
    if (responseFuture != null) {
      responseFuture.setSendRequestOK(false);
      responseFuture.putResponse(null);
      try {
        executeInvokeCallback(responseFuture);
      } catch (Throwable e) {
        log.warn("execute callback in requestFail, and callback throw", e);
      } finally {
        responseFuture.release();
      }
    }
  }

  /**
   * mark the request of the specified channel as fail and to invoke fail callback immediately
   *
   * @param channel the channel which is close already
   */
  protected void failFast(final Channel channel) {
    Iterator<Entry<Integer, ResponseFuture>> it = responseTable.entrySet().iterator();
    while (it.hasNext()) {
      Entry<Integer, ResponseFuture> entry = it.next();
      if (entry.getValue().getProcessChannel() == channel) {
        Integer opaque = entry.getKey();
        if (opaque != null) {
          requestFail(opaque);
        }
      }
    }
  }

  public void invokeOnewayImpl(
      final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
          RemoteSendRequestException {
    request.markOnewayRPC();
    boolean acquired = this.semaphoreOneway.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    if (acquired) {
      final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreOneway);
      try {
        channel
            .writeAndFlush(request)
            .addListener(
                (ChannelFutureListener)
                    f -> {
                      once.release();
                      if (!f.isSuccess()) {
                        log.warn(
                            "send a request command to channel <"
                                + channel.remoteAddress()
                                + "> failed.");
                      }
                    });
      } catch (Exception e) {
        once.release();
        log.warn(
            "write send a request command to channel <" + channel.remoteAddress() + "> failed.");
        throw new RemoteSendRequestException(parseChannelRemoteAddr(channel), e);
      }
    } else {
      if (timeoutMillis <= 0) {
        throw new RemoteTooMuchRequestException("invokeOnewayImpl invoke too fast");
      } else {
        String info =
            String.format(
                "invokeOnewayImpl tryAcquire semaphore timeout, %dms, waiting thread nums: %d"
                    + " semaphoreAsyncValue: %d",
                timeoutMillis,
                this.semaphoreOneway.getQueueLength(),
                this.semaphoreOneway.availablePermits());
        log.warn(info);
        throw new RemoteTimeoutException(info);
      }
    }
  }

  class NettyEventExecutor extends ServiceThread {
    private final LinkedBlockingQueue<NettyEvent> eventQueue = new LinkedBlockingQueue<>();
    private final int maxSize = 10000;

    public void putNettyEvent(final NettyEvent event) {
      if (this.eventQueue.size() <= maxSize) {
        this.eventQueue.add(event);
      } else {
        log.warn(
            "event queue size[{}] enough, so drop this event {}",
            this.eventQueue.size(),
            event.toString());
      }
    }

    @Override
    public void run() {
      log.info(this.getServiceName() + " service started");

      final ChannelEventListener listener = NettyRemoteAbstract.this.getChannelEventListener();

      while (!this.isStopped()) {
        try {
          NettyEvent event = this.eventQueue.poll(3000, TimeUnit.MILLISECONDS);
          if (event != null && listener != null) {
            switch (event.getType()) {
              case IDLE:
                {
                  listener.onChannelIdle(event.getRemoteAddr(), event.getChannel());
                  break;
                }
              case CLOSE:
                {
                  listener.onChannelClose(event.getRemoteAddr(), event.getChannel());
                  break;
                }
              case CONNECT:
                {
                  listener.onChannelConnect(event.getRemoteAddr(), event.getChannel());
                  break;
                }
              case EXCEPTION:
                {
                  NettyExceptionEvent nee = (NettyExceptionEvent) event;
                  listener.onChannelException(
                      nee.getRemoteAddr(), nee.getChannel(), nee.getCause());
                  break;
                }
              default:
                break;
            }
          }
        } catch (Exception e) {
          log.warn(this.getServiceName() + " service has exception. ", e);
        }
      }

      log.info(this.getServiceName() + " service end");
    }

    @Override
    public String getServiceName() {
      return NettyEventExecutor.class.getSimpleName();
    }
  }
}

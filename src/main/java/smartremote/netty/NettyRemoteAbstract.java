package smartremote.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smartremote.ChannelEventListener;
import smartremote.RPCHook;
import smartremote.common.Pair;
import smartremote.common.RemoteHelper;
import smartremote.common.SemaphoreReleaseOnlyOnce;
import smartremote.common.ServiceThread;
import smartremote.errors.RemoteException;
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

  protected final Semaphore semaphoreOneway;

  protected final Semaphore semaphoreAsync;

  protected final ConcurrentMap<Integer, ResponseFuture> responseTable =
      new ConcurrentHashMap<>(256);

  protected final HashMap<Integer /* request code */, Pair<NettyRequestProcessor, ExecutorService>>
      processorTable = new HashMap<>(64);

  protected final NettyEventExecutor nettyEventExecutor = new NettyEventExecutor();

  protected Pair<NettyRequestProcessor, ExecutorService> defaultRequestProcessor;

  protected volatile SslContext sslContext;

  protected List<RPCHook> rpcHooks = new ArrayList<>();

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
    rpcHooks.forEach(hook -> hook.runBeforeRequest(addr, request));
  }

  protected void runAfterRpcHooks(String addr, RemoteCmd request, RemoteCmd response) {
    rpcHooks.forEach(hook -> hook.runAfterResponse(addr, request, response));
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
        runInvokeCallback(future);
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

  private void runInvokeCallback(final ResponseFuture future) {
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

  public abstract ExecutorService getCallbackExecutor();

  public void scanResponseTable() {
    final List<ResponseFuture> futures = new LinkedList<>();
    Iterator<Entry<Integer, ResponseFuture>> iterator = this.responseTable.entrySet().iterator();
    while (iterator.hasNext()) {
      Entry<Integer, ResponseFuture> next = iterator.next();
      ResponseFuture rep = next.getValue();

      if ((rep.getBeginTimestamp() + rep.getTimeoutMillis() + 1000) <= System.currentTimeMillis()) {
        rep.release();
        iterator.remove();
        futures.add(rep);
        log.error("remove timeout request, " + rep);
      }
    }

    for (ResponseFuture future : futures) {
      try {
        runInvokeCallback(future);
      } catch (Throwable e) {
        log.error("scanResponseTable, operationComplete Exception", e);
      }
    }
  }

  public RemoteCmd syncCall(
      final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteException {

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
                    }
                    future.setSendRequestOK(false);
                    responseTable.remove(opaque);
                    future.setCause(f.cause());
                    future.putResponse(null);
                    log.warn("send a request command to channel <" + addr + "> failed.");
                  });

      RemoteCmd response;
      if ((response = future.waitResponse(timeoutMillis)) == null) {
        throw future.isSendRequestOK()
            ? new RemoteTimeoutException(
                parseSocketAddressAddr(addr), timeoutMillis, future.getCause())
            : new RemoteSendRequestException(parseSocketAddressAddr(addr), future.getCause());
      }
      return response;
    } finally {
      this.responseTable.remove(opaque);
    }
  }

  public void asyncCall(
      final Channel channel,
      final RemoteCmd request,
      final long timeoutMillis,
      final InvokeCallback callback)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
          RemoteSendRequestException {

    long beginStartTime = System.currentTimeMillis();
    final int opaque = request.getOpaque();

    if (this.semaphoreAsync.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {

      final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreAsync);
      long costTime = System.currentTimeMillis() - beginStartTime;

      if (timeoutMillis < costTime) {
        once.release();
        throw new RemoteTimeoutException("asyncCall call timeout");
      }

      final ResponseFuture future =
          new ResponseFuture(channel, opaque, timeoutMillis - costTime, callback, once);
      this.responseTable.put(opaque, future);

      try {
        channel
            .writeAndFlush(request)
            .addListener(
                (ChannelFutureListener)
                    f -> {
                      if (f.isSuccess()) {
                        future.setSendRequestOK(true);
                        return;
                      }
                      requestFail(opaque);
                      log.warn(
                          "send a request command to channel <{}> failed.",
                          parseChannelRemoteAddr(channel));
                    });

        return;
      } catch (Exception e) {
        future.release();
        log.warn(
            "send a request command to channel <" + parseChannelRemoteAddr(channel) + "> Exception",
            e);
        throw new RemoteSendRequestException(parseChannelRemoteAddr(channel), e);
      }
    }

    if (timeoutMillis <= 0) {
      throw new RemoteTooMuchRequestException("asyncCall invoke too fast");
    }

    String info =
        String.format(
            "asyncCall tryAcquire semaphore timeout, %dms, waiting thread nums: %d"
                + " semaphoreAsyncValue: %d",
            timeoutMillis,
            this.semaphoreAsync.getQueueLength(),
            this.semaphoreAsync.availablePermits());
    log.error(info);

    throw new RemoteTimeoutException(info);
  }

  private void requestFail(final int opaque) {
    ResponseFuture future;
    if ((future = responseTable.remove(opaque)) != null) {
      future.setSendRequestOK(false);
      future.putResponse(null);
      try {
        runInvokeCallback(future);
      } catch (Throwable e) {
        log.warn("execute callback in requestFail, and callback throw", e);
      } finally {
        future.release();
      }
    }
  }

  protected void failFast(final Channel channel) {
    for (Entry<Integer, ResponseFuture> entry : responseTable.entrySet()) {
      if (entry.getValue().getProcessChannel() == channel) {
        Integer opaque;
        if ((opaque = entry.getKey()) != null) {
          requestFail(opaque);
        }
      }
    }
  }

  public void onewayCall(final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
          RemoteSendRequestException {

    request.markOnewayRPC();
    if (this.semaphoreOneway.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
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

        return;
      } catch (Exception e) {
        once.release();
        log.warn(
            "write send a request command to channel <" + channel.remoteAddress() + "> failed.");
        throw new RemoteSendRequestException(parseChannelRemoteAddr(channel), e);
      }
    }
    if (timeoutMillis <= 0) {
      throw new RemoteTooMuchRequestException("onewayCall invoke too fast");
    }

    String info =
        String.format(
            "onewayCall tryAcquire semaphore timeout, %dms, waiting thread nums: %d"
                + " semaphoreAsyncValue: %d",
            timeoutMillis,
            this.semaphoreOneway.getQueueLength(),
            this.semaphoreOneway.availablePermits());
    log.warn(info);
    throw new RemoteTimeoutException(info);
  }

  class NettyEventExecutor extends ServiceThread {
    private final LinkedBlockingQueue<NettyEvent> eventQueue = new LinkedBlockingQueue<>();
    private final int maxSize = 10000;

    public void putNettyEvent(final NettyEvent event) {
      if (this.eventQueue.size() <= maxSize) {
        this.eventQueue.add(event);
        return;
      }
      log.warn(
          "event queue size[{}] enough, so drop this event {}",
          this.eventQueue.size(),
          event.toString());
    }

    @Override
    public void run() {
      log.info(this.getServiceName() + " service started");

      final ChannelEventListener listener = NettyRemoteAbstract.this.getChannelEventListener();
      while (!this.isStopped()) {
        try {
          NettyEvent event;
          if ((event = this.eventQueue.poll(3000, TimeUnit.MILLISECONDS)) != null
              && listener != null) {
            switch (event.getType()) {
              case IDLE:
                listener.onChannelIdle(event.getRemoteAddr(), event.getChannel());
                break;
              case CLOSE:
                listener.onChannelClose(event.getRemoteAddr(), event.getChannel());
                break;
              case CONNECT:
                listener.onChannelConnect(event.getRemoteAddr(), event.getChannel());
                break;
              case EXCEPTION:
                NettyExceptionEvent nee = (NettyExceptionEvent) event;
                listener.onChannelException(nee.getRemoteAddr(), nee.getChannel(), nee.getCause());
                break;
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

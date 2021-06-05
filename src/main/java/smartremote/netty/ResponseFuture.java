package smartremote.netty;

import io.netty.channel.Channel;
import lombok.Data;
import smartremote.InvokeCallback;
import smartremote.common.SemaphoreReleaseOnlyOnce;
import smartremote.protocol.RemoteCmd;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Data
public class ResponseFuture {

  private final int opaque;
  private final Channel processChannel;
  private final long timeoutMillis;
  private final InvokeCallback callback;
  private final long beginTimestamp = System.currentTimeMillis();
  private final CountDownLatch countDownLatch = new CountDownLatch(1);
  private final SemaphoreReleaseOnlyOnce once;
  private final AtomicBoolean executeCallbackOnlyOnce = new AtomicBoolean(false);
  private volatile RemoteCmd response;
  private volatile boolean sendRequestOK = true;
  private volatile Throwable cause;

  public ResponseFuture(
      Channel channel,
      int opaque,
      long timeoutMillis,
      InvokeCallback callback,
      SemaphoreReleaseOnlyOnce once) {
    this.opaque = opaque;
    this.processChannel = channel;
    this.timeoutMillis = timeoutMillis;
    this.callback = callback;
    this.once = once;
  }

  public void runInvokeCallback() throws InterruptedException {
    if (callback != null) {
      if (this.executeCallbackOnlyOnce.compareAndSet(false, true)) {
        callback.operationComplete(this);
      }
    }
  }

  public void release() {
    if (this.once != null) {
      this.once.release();
    }
  }

  public boolean isTimeout() {
    return (System.currentTimeMillis() - this.beginTimestamp) > this.timeoutMillis;
  }

  public RemoteCmd waitResponse(final long timeoutMillis) throws InterruptedException {
    this.countDownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    return this.response;
  }

  public void putResponse(final RemoteCmd response) {
    this.response = response;
    this.countDownLatch.countDown();
  }

  @Override
  public String toString() {
    return "ResponseFuture [responseCommand="
        + response
        + ", sendRequestOK="
        + sendRequestOK
        + ", cause="
        + cause
        + ", opaque="
        + opaque
        + ", processChannel="
        + processChannel
        + ", timeoutMillis="
        + timeoutMillis
        + ", invokeCallback="
        + callback
        + ", beginTimestamp="
        + beginTimestamp
        + ", countDownLatch="
        + countDownLatch
        + "]";
  }
}

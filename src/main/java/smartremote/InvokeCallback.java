package smartremote;

import smartremote.netty.ResponseFuture;

public interface InvokeCallback {
  void operationComplete(final ResponseFuture responseFuture) throws InterruptedException;
}

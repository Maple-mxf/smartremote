package org.smartkola.remote;

import org.smartkola.remote.netty.ResponseFuture;

public interface InvokeCallback {
  void operationComplete(final ResponseFuture responseFuture) throws InterruptedException;
}

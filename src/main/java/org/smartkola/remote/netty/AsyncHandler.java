package org.smartkola.remote.netty;

import io.netty.channel.ChannelHandlerContext;
import org.smartkola.remote.protocol.RemoteCmd;

public abstract class AsyncHandler implements Handler {

  public void asyncProcessRequest(
      ChannelHandlerContext ctx, RemoteCmd request, RemoteResponseCallback callback)
      throws Exception {
    RemoteCmd response = hand(ctx, request);
    callback.call(response);
  }
}

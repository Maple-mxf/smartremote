package org.smartkola.remote.netty;

import io.netty.channel.ChannelHandlerContext;
import org.smartkola.remote.protocol.RemoteCmd;

public abstract class AsyncNettyRequestProcessor implements NettyRequestProcessor {

  public void asyncProcessRequest(
          ChannelHandlerContext ctx, RemoteCmd request, RemoteResponseCallback callback)
      throws Exception {
    RemoteCmd response = processRequest(ctx, request);
    callback.call(response);
  }
}

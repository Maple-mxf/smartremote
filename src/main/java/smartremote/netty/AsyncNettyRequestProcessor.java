package smartremote.netty;

import io.netty.channel.ChannelHandlerContext;
import smartremote.protocol.RemoteCmd;

public abstract class AsyncNettyRequestProcessor implements NettyRequestProcessor {

  public void asyncProcessRequest(
      ChannelHandlerContext ctx, RemoteCmd request, RemoteResponseCallback callback)
      throws Exception {
    RemoteCmd response = processRequest(ctx, request);
    callback.call(response);
  }
}

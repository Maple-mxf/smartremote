package smartremote.netty;

import io.netty.channel.ChannelHandlerContext;
import smartremote.protocol.RemoteCmd;

public interface NettyRequestProcessor {

  RemoteCmd processRequest(ChannelHandlerContext ctx, RemoteCmd request)
      throws Exception;

  boolean rejectRequest();
}

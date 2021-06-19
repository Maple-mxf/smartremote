package org.smartkola.remote.netty;

import io.netty.channel.ChannelHandlerContext;
import org.smartkola.remote.protocol.RemoteCmd;

public interface Handler {

  RemoteCmd hand(ChannelHandlerContext ctx, RemoteCmd request) throws Exception;

  default boolean rejectRequest() {
    return false;
  }
}

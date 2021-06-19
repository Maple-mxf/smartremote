package org.smartkola.remote.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.smartkola.remote.protocol.RemoteCmd;

import java.util.List;

public class RemoteCmdDecoder extends MessageToMessageDecoder<ByteBuf> {

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
    RemoteCmd cmd = RemoteCmd.decode0(msg);
    if (cmd != null) {
      out.add(cmd);
      System.err.println(cmd);
    }
  }
}

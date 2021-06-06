package smartremote.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import smartremote.protocol.RemoteCmd;

import java.nio.ByteBuffer;

@ChannelHandler.Sharable
public class CmdEncoder extends MessageToByteEncoder<RemoteCmd> {

  @Override
  public void encode(ChannelHandlerContext ctx, RemoteCmd cmd, ByteBuf out) {
    try {
      out.writeBytes(RemoteCmd.encode0(cmd));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

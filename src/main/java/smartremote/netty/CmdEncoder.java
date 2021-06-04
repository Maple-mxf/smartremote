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
  public void encode(ChannelHandlerContext ctx, RemoteCmd remoteCmd, ByteBuf out) {
    try {
      ByteBuffer header = remoteCmd.encodeHeader();
      out.writeBytes(header);
      byte[] body = remoteCmd.getBody();
      if (body != null) {
        out.writeBytes(body);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

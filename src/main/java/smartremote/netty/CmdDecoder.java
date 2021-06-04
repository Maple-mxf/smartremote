package smartremote.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import smartremote.protocol.RemoteCmd;

import java.nio.ByteBuffer;

public class CmdDecoder extends LengthFieldBasedFrameDecoder {

  private static final int FRAME_MAX_LENGTH =
      Integer.parseInt(System.getProperty("restdoc.frameMaxLength", "16777216"));

  public CmdDecoder() {
    super(FRAME_MAX_LENGTH, 0, 4, 0, 4);
  }

  @Override
  protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
    ByteBuf frame = null;
    try {
      frame = (ByteBuf) super.decode(ctx, in);
      if (null == frame) return null;
      ByteBuffer byteBuffer = frame.nioBuffer();
      return RemoteCmd.decode(byteBuffer);
    } catch (Exception e) {
      ctx.close();
    } finally {
      if (null != frame) {
        frame.release();
      }
    }
    return null;
  }
}

package org.smartkola.remote.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.smartkola.remote.protocol.RemoteCmd;

public class CmdDecoder extends LengthFieldBasedFrameDecoder {

  private static final int FRAME_MAX_LENGTH =
      Integer.parseInt(System.getProperty("frameMaxLength", "16777216"));

  public CmdDecoder() {
    super(FRAME_MAX_LENGTH, 0, 4, 0, 4);
  }

  @Override
  protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
    ByteBuf frame = null;
    try {
      if ((frame = (ByteBuf) super.decode(ctx, in)) == null) return null;
      return RemoteCmd.decode0(frame);
    } catch (Exception e) {
      ctx.close();
      return null;
    } finally {
      if (null != frame) frame.release();
    }
  }
}

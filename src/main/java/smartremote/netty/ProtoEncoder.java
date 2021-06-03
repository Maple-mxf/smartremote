package smartremote.netty;

import com.google.protobuf.MessageLite;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import smartremote.protobuf.ProtoMsgDefinition;
import smartremote.protobuf.ProtoMsgDefinitionTable;

import java.util.NoSuchElementException;

@ChannelHandler.Sharable
public class ProtoEncoder extends MessageToByteEncoder<MessageLite> {

  @Override
  protected void encode(ChannelHandlerContext ctx, MessageLite msg, ByteBuf out) {
    byte[] body = msg.toByteArray();
    byte[] header = encodeHeader(msg, (short) body.length);

    out.writeBytes(header);
    out.writeBytes(body);
  }

  private byte[] encodeHeader(MessageLite msg, short bodyLen) {
    ProtoMsgDefinition definition =
        ProtoMsgDefinitionTable.getInstance().getDefinitions().stream()
            .filter(t -> msg.getClass() == t.getMsgType())
            .findFirst()
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        String.format("Not found msg type equals %s", msg.getClass())));

    byte[] header = new byte[4];
    header[0] = (byte) (bodyLen & 0xff);
    header[1] = (byte) ((bodyLen >> 8) & 0xff);
    header[2] = 0;
    header[3] = definition.getMark();

    return header;
  }
}

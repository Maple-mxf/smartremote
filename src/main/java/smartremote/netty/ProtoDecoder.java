package smartremote.netty;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import smartremote.protobuf.ProtoMsgDefinition;
import smartremote.protobuf.ProtoMsgDefinitionTable;

import java.util.List;
import java.util.NoSuchElementException;

public class ProtoDecoder extends ByteToMessageDecoder {

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws InvalidProtocolBufferException {
    if (in.readableBytes() < 4) return;

    in.markReaderIndex();

    byte low = in.readByte();
    byte high = in.readByte();
    short s0 = (short) (low & 0xff);
    short s1 = (short) (high & 0xff);
    s1 <<= 8;
    short len = (short) (s0 | s1);
    in.readByte();
    byte msgMark = in.readByte();

    if (in.readableBytes() < len) {
      in.resetReaderIndex();
      return;
    }

    ByteBuf buf = in.readBytes(len);
    byte[] array;
    int offset;
    if (buf.hasArray()) {
      array = buf.array();
      offset = buf.arrayOffset() + buf.readerIndex();
    } else {
      array = new byte[len];
      offset = 0;
    }

    ProtoMsgDefinition definition =
        ProtoMsgDefinitionTable.getInstance().getDefinitions().stream()
            .filter(e -> e.getMark() == msgMark)
            .findFirst()
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        String.format("Not found msg mark equals %s", msgMark)));

    out.add(definition.getInstanceFactory().get().getParserForType().parseFrom(array, offset, len));
  }
}

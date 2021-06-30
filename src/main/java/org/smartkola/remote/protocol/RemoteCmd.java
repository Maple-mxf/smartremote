package org.smartkola.remote.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Data;
import org.smartkola.remote.protobuf.ProtoMsgDefinitionTable;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// ===============Header===============
// index     desc            max length   default
// [0.4) :   body length     int32          no default value
// [4,8) :   request code    int32          no default value
// [8,9) :   cmd type        byte           1
// [9,10):   rpc type        byte8          1
// [10,11):  serialize type  byte8          1
// [11,15):  msg type        int32         -1

@Data
public class RemoteCmd {

  @Deprecated private static final int RPC_TYPE = 0;
  @Deprecated private static final int RPC_ONEWAY = 1;

  private static final AtomicInteger REQUEST_ID = new AtomicInteger(0);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private int code;
  private int flag = 0;
  private int rpcType = RpcType.SYNC.getType();
  private SerializeType serializeType = SerializeType.PROTOBUF;
  private transient byte[] body;
  private int msgType = -1;
  private int opaque = REQUEST_ID.getAndIncrement();
  private transient Object msgValue;

  protected RemoteCmd() {}

  public static RemoteCmd newRequest(int code) {
    RemoteCmd cmd = new RemoteCmd();
    cmd.setCode(code);
    return cmd;
  }

  public static RemoteCmd newResponse(int code) {
    RemoteCmd cmd = new RemoteCmd();
    cmd.setCode(code);
    cmd.flag = 1;

    return cmd;
  }

  public static RemoteCmd decode0(final ByteBuf buf) {
    int bufLen;
    if ((bufLen = buf.readableBytes()) < 14) return null;

    buf.markReaderIndex();
    int bodyLen = buf.readInt();
    if (bufLen < 14 + bodyLen) {
      buf.resetReaderIndex();
      return null;
    }

    int requestCode = buf.readInt();
    byte cmdType = buf.readByte();
    byte rpcType = buf.readByte();
    byte serializeType = buf.readByte();
    int msgType = buf.readInt();

    byte[] body = new byte[bodyLen];
    buf.readBytes(bodyLen).readBytes(body);

    RemoteCmd cmd = new RemoteCmd();
    cmd.body = body;
    cmd.serializeType = SerializeType.valueOf(serializeType);
    cmd.flag = cmdType;
    cmd.code = requestCode;
    cmd.msgType = msgType;
    cmd.rpcType = rpcType;

    return cmd;
  }

  public static ByteBuf encode0(final RemoteCmd cmd) {
    ByteBuf buf = Unpooled.buffer();

    // write header
    int bodyLen = (cmd.body == null ? 0 : cmd.body.length);
    buf.writeInt(bodyLen);
    buf.writeInt(cmd.code);
    buf.writeByte(cmd.flag);
    buf.writeByte(cmd.rpcType);
    buf.writeByte(cmd.serializeType.getCode());
    buf.writeInt(cmd.msgType);

    // write body
    buf.writeBytes(cmd.body);

    return buf;
  }

  public void markOnewayRPC() {
    int bits = 1 << RPC_ONEWAY;
    this.flag |= bits;
  }

  public boolean isOnewayRPC() {
    int bits = 1 << RPC_ONEWAY;
    return (this.flag & bits) == bits;
  }

  public RemoteCmdType getType() {
    return this.isResponseType() ? RemoteCmdType.RESPONSE_COMMAND : RemoteCmdType.REQUEST_COMMAND;
  }

  public boolean isResponseType() {
    int bits = 1 << RPC_TYPE;
    return (this.flag & bits) == bits;
  }

  public <T> T mapToObj() throws InvalidProtocolBufferException {
    Object obj = null;
    if (serializeType == SerializeType.PROTOBUF) {
      MessageLite ml =
          ProtoMsgDefinitionTable.getInstance().getDefinitions().stream()
              .filter(t -> t.getMark() == this.msgType)
              .findFirst()
              .orElseThrow(
                  () ->
                      new NoSuchElementException(
                          String.format("Not found msg code type : %d", msgType)))
              .getInstanceFactory()
              .get();
      ml.getParserForType().parseFrom(this.body);
      obj = ml;
    } else {
      // MAPPER. TODO
    }
    return (T) obj;
  }
}

package smartremote.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Data;
import smartremote.CmdHeader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

  public static RemoteCmd newResponse(Class<? extends CmdHeader> classHeader) {
    return newResponse(ResponseCode.SYSTEM_ERROR, "not set any response code", classHeader);
  }

  public static RemoteCmd newResponse(
      int code, String remark, Class<? extends CmdHeader> classHeader) {
    RemoteCmd cmd = new RemoteCmd();
    cmd.setCode(code);

    return cmd;
  }

  public static RemoteCmd newResponse(int code, String remark) {
    return newResponse(code, remark, null);
  }

  public static RemoteCmd decode(final byte[] array) {
    return decode(ByteBuffer.wrap(array));
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

    int requestCode = buf.readInt() /*& 0xff*/;
    byte cmdType = buf.readByte();
    byte rpcType = buf.readByte();
    byte serializeType = buf.readByte();
    int msgType = buf.readInt();
    byte[] body = buf.readBytes(bodyLen).array();

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
    return null;
  }

  public static RemoteCmd decode(final java.nio.ByteBuffer buf) {
    int len = buf.limit();
    int originHeaderLen = buf.getInt();
    int headerLen = getHeaderLength(originHeaderLen);

    byte[] header = new byte[headerLen];
    buf.get(header);

    RemoteCmd cmd = decodeHeader(header, getProtocolType(originHeaderLen));
    if (cmd == null) return null;

    int bodyLen = len - 4 - headerLen;
    byte[] body = null;
    if (bodyLen > 0) {
      body = new byte[bodyLen];
      buf.get(body);
    }
    cmd.body = body;

    return cmd;
  }

  public static int getHeaderLength(int length) {
    return length & 0xFFFFFF;
  }

  private static RemoteCmd decodeHeader(byte[] headerData, SerializeType type) {
    switch (type) {
      case JSON:
        RemoteCmd cmd = RemoteSerializable.decode(headerData, RemoteCmd.class);
        cmd.setSerializeType(type);
        return cmd;
      default:
        break;
    }

    return null;
  }

  public static SerializeType getProtocolType(int source) {
    return SerializeType.valueOf((byte) ((source >> 24) & 0xFF));
  }

  @Deprecated
  public ByteBuffer encode() {
    return null;
  }

  @Deprecated
  public ByteBuffer encodeHeader() {
    return null;
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
}

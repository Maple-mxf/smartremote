package smartremote.protocol;

import lombok.Data;
import smartremote.CmdHeader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class RemoteCmd {

  private static final int RPC_TYPE = 0;
  private static final int RPC_ONEWAY = 1;
  private static final Map<Class<? extends CmdHeader>, Field[]> CLASS_HASH_MAP = new HashMap<>();
  private static final Map<Class, String> CANONICAL_NAME_CACHE = new HashMap<>();
  private static final Map<Field, Boolean> NULLABLE_FIELD_CACHE = new HashMap<>();
  private static final AtomicInteger REQUEST_ID = new AtomicInteger(0);
  private static SerializeType serializeTypeConfigInThisServer = SerializeType.PROTOBUF;

  private int code;
  private int opaque = REQUEST_ID.getAndIncrement();
  private int flag = 0;
  private String remark;
  private HashMap<String, String> extFields;
  private transient CmdHeader header;
  private SerializeType serializeTypeCurrentRPC = serializeTypeConfigInThisServer;
  private transient byte[] body;

  protected RemoteCmd() {}

  public static RemoteCmd newRequest(int code, CmdHeader customHeader) {
    RemoteCmd cmd = new RemoteCmd();
    cmd.setCode(code);
    cmd.header = customHeader;
    return cmd;
  }

  public static RemoteCmd newResponse(Class<? extends CmdHeader> classHeader) {
    return newResponse(ResponseCode.SYSTEM_ERROR, "not set any response code", classHeader);
  }

  public static RemoteCmd newResponse(
      int code, String remark, Class<? extends CmdHeader> classHeader) {
    RemoteCmd cmd = new RemoteCmd();
    cmd.setCode(code);
    cmd.setRemark(remark);

    if (classHeader != null) {
      try {
        cmd.header = classHeader.newInstance();
      } catch (InstantiationException | IllegalAccessException e) {
        return null;
      }
    }
    return cmd;
  }

  public static RemoteCmd newResponse(int code, String remark) {
    return newResponse(code, remark, null);
  }

  public static RemoteCmd decode(final byte[] array) {
    return decode(ByteBuffer.wrap(array));
  }

  public static RemoteCmd decode(final ByteBuffer buf) {
    int len = buf.limit();
    int oriHeaderLen = buf.getInt();
    int headerLen = getHeaderLength(oriHeaderLen);

    byte[] header = new byte[headerLen];
    buf.get(header);

    RemoteCmd cmd = decodeHeader(header, getProtocolType(oriHeaderLen));

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
        cmd.setSerializeTypeCurrentRPC(type);
        return cmd;
      default:
        break;
    }

    return null;
  }

  public static SerializeType getProtocolType(int source) {
    return SerializeType.valueOf((byte) ((source >> 24) & 0xFF));
  }

  public ByteBuffer encode() {
    return null;
  }

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

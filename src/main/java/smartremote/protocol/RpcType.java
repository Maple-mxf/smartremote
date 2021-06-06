package smartremote.protocol;

public enum RpcType {

  ONEWAY((byte) 0),
  ASYNC((byte) 1),
  SYNC((byte) 2),
  ;

  private final byte type;

  RpcType(byte type) {
    this.type = type;
  }

  public byte getType() {
    return type;
  }
}

package smartremote.netty;

import io.netty.buffer.ByteBuf;
import smartremote.protocol.RemoteCmd;
import smartremote.protocol.SerializeType;

interface Serialization {

  SerializeType serializeType();

  ByteBuf encodeHeader(RemoteCmd cmd);

  ByteBuf encodeBody(RemoteCmd cmd);
}

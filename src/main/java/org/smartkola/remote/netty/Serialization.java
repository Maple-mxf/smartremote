package org.smartkola.remote.netty;

import io.netty.buffer.ByteBuf;
import org.smartkola.remote.protocol.RemoteCmd;
import org.smartkola.remote.protocol.SerializeType;

interface Serialization {

  SerializeType serializeType();

  ByteBuf encodeHeader(RemoteCmd cmd);

  ByteBuf encodeBody(RemoteCmd cmd);
}

package org.smartkola.remote.protobuf;

import com.google.protobuf.MessageLite;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.smartkola.remote.annotation.MsgType;

import java.util.function.Supplier;

@Data
@AllArgsConstructor
@MsgType(value = 1)
public class ProtoMsgDefinition {
  private int mark;
  private Class<? extends MessageLite> msgType;
  private Supplier<? extends MessageLite> instanceFactory;
}

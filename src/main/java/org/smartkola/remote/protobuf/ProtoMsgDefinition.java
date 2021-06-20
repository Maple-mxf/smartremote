package org.smartkola.remote.protobuf;

import com.google.protobuf.MessageLite;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.function.Supplier;

@Data
@AllArgsConstructor
public class ProtoMsgDefinition {
  private int mark;
  private Class<? extends MessageLite> msgType;
  private Supplier<? extends MessageLite> instanceFactory;
}

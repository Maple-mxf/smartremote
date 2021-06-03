package smartremote.protobuf;

import com.google.protobuf.MessageLite;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.function.Supplier;

@Data
@AllArgsConstructor
public class ProtoMsgDefinition {
  private byte mark;
  private Class<? extends MessageLite> msgType;
  private Supplier<? extends MessageLite> instanceFactory;
}

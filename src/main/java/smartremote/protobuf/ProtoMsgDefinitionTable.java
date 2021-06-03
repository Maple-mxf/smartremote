package smartremote.protobuf;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ProtoMsgDefinitionTable {

  private static final ProtoMsgDefinitionTable INSTANCE = new ProtoMsgDefinitionTable();

  private final List<ProtoMsgDefinition> definitions = new CopyOnWriteArrayList<>();

  private ProtoMsgDefinitionTable() {}

  public static ProtoMsgDefinitionTable getInstance() {
    return INSTANCE;
  }

  public void addMsgDefinition(ProtoMsgDefinition definition) {
    this.definitions.add(definition);
  }

  public List<ProtoMsgDefinition> getDefinitions() {
    return ImmutableList.copyOf(definitions);
  }
}

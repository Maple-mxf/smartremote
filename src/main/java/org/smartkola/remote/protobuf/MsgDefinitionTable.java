package org.smartkola.remote.protobuf;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MsgDefinitionTable {

  private static final MsgDefinitionTable INSTANCE = new MsgDefinitionTable();

  private final List<MsgDefinition> definitions = new CopyOnWriteArrayList<>();

  private MsgDefinitionTable() {}

  public static MsgDefinitionTable getInstance() {
    return INSTANCE;
  }

  public MsgDefinitionTable addMsgDefinition(MsgDefinition definition) {
    this.definitions.add(definition);
    return this;
  }

  public List<MsgDefinition> getDefinitions() {
    return ImmutableList.copyOf(definitions);
  }
}

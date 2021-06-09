package org.smartkola.remote.netty;

import org.junit.Test;
import org.mockito.Mockito;
import org.smartkola.remote.protocol.RemoteCmd;

import static org.mockito.Mockito.mock;

public class RemoteCmdTest {

  @Test
  public void testCmdDecodeOfProto() {
    RemoteCmd cmd = mock(RemoteCmd.class);
    // Mockito.when(cmd.encodeHeader()).thenReturn(null);


  }
}

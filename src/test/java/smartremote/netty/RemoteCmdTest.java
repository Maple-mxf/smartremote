package smartremote.netty;

import org.junit.Test;
import org.mockito.Mockito;
import smartremote.protocol.RemoteCmd;

import static org.mockito.Mockito.mock;

public class RemoteCmdTest {

  @Test
  public void testCmdDecodeOfProto() {
    RemoteCmd cmd = mock(RemoteCmd.class);
    Mockito.when(cmd.encodeHeader()).thenReturn(null);


  }
}

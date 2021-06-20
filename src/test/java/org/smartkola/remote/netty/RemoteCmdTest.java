package org.smartkola.remote.netty;

import org.junit.Test;
import org.mockito.Mockito;
import org.smartkola.remote.protocol.RemoteCmd;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

public class RemoteCmdTest {

  @Test
  public void testCmdDecodeOfProto() {
    RemoteCmd cmd = mock(RemoteCmd.class);
    // Mockito.when(cmd.encodeHeader()).thenReturn(null);
  }

  @Test
  public void testAtomicInt() {
    AtomicInteger atomicInteger = new AtomicInteger(Integer.MAX_VALUE);
    for (int i = 0; i < 100; i++) {
      System.err.println(atomicInteger.getAndIncrement());
    }
  }
}

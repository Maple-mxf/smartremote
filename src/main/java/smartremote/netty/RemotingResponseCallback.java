package smartremote.netty;

import smartremote.protocol.RemoteCmd;

public interface RemotingResponseCallback {
  void callback(RemoteCmd response);
}

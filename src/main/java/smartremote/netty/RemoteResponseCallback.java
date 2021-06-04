package smartremote.netty;

import smartremote.protocol.RemoteCmd;

public interface RemoteResponseCallback {
  void call(RemoteCmd response);
}

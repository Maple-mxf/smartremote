package org.smartkola.remote.netty;

import org.smartkola.remote.protocol.RemoteCmd;

public interface RemoteResponseCallback {
  void call(RemoteCmd response);
}

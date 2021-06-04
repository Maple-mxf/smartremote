package smartremote;

import smartremote.protocol.RemoteCmd;

public interface RPCHook {

  void runBeforeRequest(final String remoteAddr, final RemoteCmd request);

  void runAfterResponse(
          final String remoteAddr, final RemoteCmd request, final RemoteCmd response);
}

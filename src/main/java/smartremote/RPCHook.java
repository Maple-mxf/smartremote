package smartremote;

import smartremote.protocol.RemoteCmd;

public interface RPCHook {

  void doBeforeRequest(final String remoteAddr, final RemoteCmd request);

  void doAfterResponse(
          final String remoteAddr, final RemoteCmd request, final RemoteCmd response);
}

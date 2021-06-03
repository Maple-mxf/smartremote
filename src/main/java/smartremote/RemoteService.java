package smartremote;

import smartremote.errors.RemoteException;

public interface RemoteService {

  void start() throws InterruptedException, RemoteException;

  void shutdown();

  void registerRPCHook(RPCHook rpcHook);
}

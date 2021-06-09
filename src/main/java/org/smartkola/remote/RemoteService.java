package org.smartkola.remote;

import org.smartkola.remote.errors.RemoteException;

public interface RemoteService {

  void start() throws InterruptedException, RemoteException;

  void shutdown();

  void addRPCHook(RPCHook rpcHook);
}

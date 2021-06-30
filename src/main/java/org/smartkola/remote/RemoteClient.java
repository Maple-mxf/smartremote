package org.smartkola.remote;

import org.smartkola.remote.errors.RemoteConnectException;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.errors.RemoteTooMuchRequestException;
import org.smartkola.remote.netty.Handler;
import org.smartkola.remote.protocol.RemoteCmd;

import java.util.List;
import java.util.concurrent.ExecutorService;

public interface RemoteClient extends RemoteService {

  @Deprecated
  void updateNameServerAddressList(final List<String> addrs);

  @Deprecated
  List<String> getNameServerAddressList();

  RemoteCmd syncCall(final String address, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteSendRequestException,
          RemoteTimeoutException;

  void asyncCall(
      final String addr,
      final RemoteCmd request,
      final long timeoutMillis,
      final InvokeCallback invokeCallback)
      throws InterruptedException, RemoteConnectException, RemoteTooMuchRequestException,
          RemoteTimeoutException, RemoteSendRequestException;

  void onewayCall(final String addr, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteTooMuchRequestException,
          RemoteTimeoutException, RemoteSendRequestException;

  void registerHandler(
      final int requestCode, final Handler handler, final ExecutorService executor);

  void setCallbackExecutor(final ExecutorService callbackExecutor);

  ExecutorService getCallbackExecutor();

  boolean isChannelWritable(final String address);
}

package org.smartkola.remote;

import org.smartkola.remote.errors.RemoteConnectException;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.errors.RemoteTooMuchRequestException;
import org.smartkola.remote.netty.NettyRequestProcessor;
import org.smartkola.remote.protocol.RemoteCmd;

import java.util.List;
import java.util.concurrent.ExecutorService;

public interface RemoteClient extends RemoteService {

  void updateNameServerAddressList(final List<String> addrs);

  List<String> getNameServerAddressList();

  RemoteCmd syncCall(
          final String addr, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteSendRequestException,
          RemoteTimeoutException;

  void invokeAsync(
      final String addr,
      final RemoteCmd request,
      final long timeoutMillis,
      final InvokeCallback invokeCallback)
      throws InterruptedException, RemoteConnectException, RemoteTooMuchRequestException,
          RemoteTimeoutException, RemoteSendRequestException;

  void invokeOneway(final String addr, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteTooMuchRequestException,
          RemoteTimeoutException, RemoteSendRequestException;

  void registerProcessor(
          final int requestCode, final NettyRequestProcessor processor, final ExecutorService executor);

  void setCallbackExecutor(final ExecutorService callbackExecutor);

  ExecutorService getCallbackExecutor();

  boolean isChannelWritable(final String addr);
}

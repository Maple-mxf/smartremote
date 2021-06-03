package smartremote;

import smartremote.errors.RemoteConnectException;
import smartremote.errors.RemoteSendRequestException;
import smartremote.errors.RemoteTimeoutException;
import smartremote.errors.RemoteTooMuchRequestException;
import smartremote.netty.NettyRequestProcessor;
import smartremote.protocol.RemoteCmd;

import java.util.List;
import java.util.concurrent.ExecutorService;

public interface RemoteClient extends RemoteService {

  void updateNameServerAddressList(final List<String> addrs);

  List<String> getNameServerAddressList();

  RemoteCmd invokeSync(
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

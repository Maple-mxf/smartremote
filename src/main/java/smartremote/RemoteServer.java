package smartremote;

import io.netty.channel.Channel;
import smartremote.common.Pair;
import smartremote.errors.RemoteTimeoutException;
import smartremote.netty.NettyRequestProcessor;
import smartremote.protocol.RemoteCmd;
import smartremote.errors.RemoteSendRequestException;
import smartremote.errors.RemoteTooMuchRequestException;

import java.util.concurrent.ExecutorService;

public interface RemoteServer extends RemoteService {

  void registerProcessor(
          final int requestCode, final NettyRequestProcessor processor, final ExecutorService executor);

  void registerDefaultProcessor(
      final NettyRequestProcessor processor, final ExecutorService executor);

  int localListenPort();

  Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(final int requestCode);

  RemoteCmd invokeSync(
          final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteSendRequestException, RemoteTimeoutException;

  void invokeAsync(
      final Channel channel,
      final RemoteCmd request,
      final long timeoutMillis,
      final InvokeCallback invokeCallback)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
          RemoteSendRequestException;

  void invokeOneway(final Channel channel, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteTooMuchRequestException, RemoteTimeoutException,
          RemoteSendRequestException;
}

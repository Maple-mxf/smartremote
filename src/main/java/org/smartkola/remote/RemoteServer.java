package org.smartkola.remote;

import io.netty.channel.Channel;
import org.smartkola.remote.common.Pair;
import org.smartkola.remote.errors.RemoteException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.netty.Handler;
import org.smartkola.remote.protocol.RemoteCmd;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTooMuchRequestException;

import java.util.concurrent.ExecutorService;

public interface RemoteServer extends RemoteService {

  void registerHandler(
      final int requestCode, final Handler processor, final ExecutorService executor);

  void registerDefaultHandler(
      final Handler processor, final ExecutorService executor);

  int localListenPort();

  Pair<Handler, ExecutorService> getProcessorPair(final int requestCode);

  RemoteCmd invokeSync(
          final Channel channel, final RemoteCmd request, final long timeoutMillis)
          throws InterruptedException, RemoteException;

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

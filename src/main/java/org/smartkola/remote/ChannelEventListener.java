package org.smartkola.remote;

import io.netty.channel.Channel;
import org.smartkola.remote.errors.RemoteConnectException;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.errors.RemoteTooMuchRequestException;

public interface ChannelEventListener {
  default void onChannelConnect(final String remoteAddr, final Channel channel)
      throws InterruptedException, RemoteSendRequestException, RemoteTimeoutException,
          RemoteTooMuchRequestException, RemoteConnectException {}

  default void onChannelClose(final String remoteAddr, final Channel channel) {}

  default void onChannelException(
      final String remoteAddr, final Channel channel, final Throwable cause) {}

  default void onChannelIdle(final String remoteAddr, final Channel channel) {}
}

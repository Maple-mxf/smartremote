package smartremote;

import io.netty.channel.Channel;
import smartremote.errors.RemoteConnectException;
import smartremote.errors.RemoteSendRequestException;
import smartremote.errors.RemoteTimeoutException;
import smartremote.errors.RemoteTooMuchRequestException;

public interface ChannelEventListener {
  void onChannelConnect(final String remoteAddr, final Channel channel)
      throws InterruptedException, RemoteSendRequestException, RemoteTimeoutException,
          RemoteTooMuchRequestException, RemoteConnectException;

  void onChannelClose(final String remoteAddr, final Channel channel);

  void onChannelException(final String remoteAddr, final Channel channel, final Throwable cause);

  void onChannelIdle(final String remoteAddr, final Channel channel);
}

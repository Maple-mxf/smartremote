package org.smartkola.remote.common;

import io.netty.channel.Channel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.smartkola.remote.errors.RemoteConnectException;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.protocol.RemoteCmd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class RemoteHelper {

  private static final InternalLogger log = InternalLoggerFactory.getInstance(RemoteHelper.class);

  public static String exceptionSimpleDesc(final Throwable e) {
    StringBuffer sb = new StringBuffer();
    if (e != null) {
      sb.append(e.toString());

      StackTraceElement[] stackTrace = e.getStackTrace();
      if (stackTrace != null && stackTrace.length > 0) {
        StackTraceElement elment = stackTrace[0];
        sb.append(", ");
        sb.append(elment.toString());
      }
    }

    return sb.toString();
  }

  public static SocketAddress string2SocketAddress(final String addr) {
    int split = addr.lastIndexOf(":");
    String host = addr.substring(0, split);
    String port = addr.substring(split + 1);
    InetSocketAddress isa = new InetSocketAddress(host, Integer.parseInt(port));
    return isa;
  }

  public static String parseChannelRemoteAddr(final Channel channel) {
    if (null == channel) {
      return "";
    }
    SocketAddress remote = channel.remoteAddress();
    final String addr = remote != null ? remote.toString() : "";

    if (addr.length() > 0) {
      int index = addr.lastIndexOf("/");
      if (index >= 0) {
        return addr.substring(index + 1);
      }

      return addr;
    }

    return "";
  }

  public static String parseSocketAddressAddr(SocketAddress socketAddress) {
    if (socketAddress != null) {
      final String addr = socketAddress.toString();

      if (addr.length() > 0) {
        return addr.substring(1);
      }
    }
    return "";
  }
}

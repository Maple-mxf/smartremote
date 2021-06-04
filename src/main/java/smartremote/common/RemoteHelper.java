package smartremote.common;

import io.netty.channel.Channel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import smartremote.errors.RemoteConnectException;
import smartremote.errors.RemoteSendRequestException;
import smartremote.errors.RemoteTimeoutException;
import smartremote.protocol.RemoteCmd;

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

  public static RemoteCmd invokeSync(
          final String addr, final RemoteCmd request, final long timeoutMillis)
      throws InterruptedException, RemoteConnectException, RemoteSendRequestException,
          RemoteTimeoutException {
    long beginTime = System.currentTimeMillis();
    SocketAddress socketAddress = RemotingUtil.string2SocketAddress(addr);
    SocketChannel socketChannel = RemotingUtil.connect(socketAddress);
    if (socketChannel != null) {
      boolean sendRequestOK = false;
      try {
        socketChannel.configureBlocking(true);
        socketChannel.socket().setSoTimeout((int) timeoutMillis);

        ByteBuffer byteBufferRequest = request.encode();
        while (byteBufferRequest.hasRemaining()) {
          int length = socketChannel.write(byteBufferRequest);
          if (length > 0) {
            if (byteBufferRequest.hasRemaining()) {
              if ((System.currentTimeMillis() - beginTime) > timeoutMillis) {

                throw new RemoteSendRequestException(addr);
              }
            }
          } else {
            throw new RemoteSendRequestException(addr);
          }

          Thread.sleep(1);
        }

        sendRequestOK = true;

        ByteBuffer byteBufferSize = ByteBuffer.allocate(4);
        while (byteBufferSize.hasRemaining()) {
          int length = socketChannel.read(byteBufferSize);
          if (length > 0) {
            if (byteBufferSize.hasRemaining()) {
              if ((System.currentTimeMillis() - beginTime) > timeoutMillis) {

                throw new RemoteTimeoutException(addr, timeoutMillis);
              }
            }
          } else {
            throw new RemoteTimeoutException(addr, timeoutMillis);
          }

          Thread.sleep(1);
        }

        int size = byteBufferSize.getInt(0);
        ByteBuffer byteBufferBody = ByteBuffer.allocate(size);
        while (byteBufferBody.hasRemaining()) {
          int length = socketChannel.read(byteBufferBody);
          if (length > 0) {
            if (byteBufferBody.hasRemaining()) {
              if ((System.currentTimeMillis() - beginTime) > timeoutMillis) {

                throw new RemoteTimeoutException(addr, timeoutMillis);
              }
            }
          } else {
            throw new RemoteTimeoutException(addr, timeoutMillis);
          }

          Thread.sleep(1);
        }

        byteBufferBody.flip();
        return RemoteCmd.decode(byteBufferBody);
      } catch (IOException e) {
        log.error("invokeSync failure", e);

        if (sendRequestOK) {
          throw new RemoteTimeoutException(addr, timeoutMillis);
        } else {
          throw new RemoteSendRequestException(addr);
        }
      } finally {
        try {
          socketChannel.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    } else {
      throw new RemoteConnectException(addr);
    }
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

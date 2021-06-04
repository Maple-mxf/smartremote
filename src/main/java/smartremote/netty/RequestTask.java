package smartremote.netty;

import io.netty.channel.Channel;
import smartremote.protocol.RemoteCmd;

import java.util.Objects;

public class RequestTask implements Runnable {
  private final Runnable runnable;
  private final long createTimestamp = System.currentTimeMillis();
  private final Channel channel;
  private final RemoteCmd request;
  private boolean stopRun = false;

  public RequestTask(final Runnable runnable, final Channel channel, final RemoteCmd request) {
    this.runnable = runnable;
    this.channel = channel;
    this.request = request;
  }

  @Override
  public int hashCode() {
    int result = runnable != null ? runnable.hashCode() : 0;
    result = 31 * result + (int) (getCreateTimestamp() ^ (getCreateTimestamp() >>> 32));
    result = 31 * result + (channel != null ? channel.hashCode() : 0);
    result = 31 * result + (request != null ? request.hashCode() : 0);
    result = 31 * result + (isStopRun() ? 1 : 0);
    return result;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof RequestTask)) return false;

    final RequestTask that = (RequestTask) o;

    if (getCreateTimestamp() != that.getCreateTimestamp()) return false;
    if (isStopRun() != that.isStopRun()) return false;
    if (!Objects.equals(channel, that.channel)) return false;
    return request != null ? request.getOpaque() == that.request.getOpaque() : that.request == null;
  }

  public long getCreateTimestamp() {
    return createTimestamp;
  }

  public boolean isStopRun() {
    return stopRun;
  }

  public void setStopRun(final boolean stopRun) {
    this.stopRun = stopRun;
  }

  @Override
  public void run() {
    if (!this.stopRun) this.runnable.run();
  }

  public void returnResponse(int code, String remark) {
    final RemoteCmd response = RemoteCmd.newResponse(code, remark);
    response.setOpaque(request.getOpaque());
    this.channel.writeAndFlush(response);
  }
}

package org.smartkola.remote.netty;

import io.netty.channel.Channel;
import lombok.Data;

@Data
public class NettyEvent {

  private final NettyEventType type;
  private final String remoteAddr;
  private final Channel channel;

  public NettyEvent(NettyEventType type, String remoteAddr, Channel channel) {
    this.type = type;
    this.remoteAddr = remoteAddr;
    this.channel = channel;
  }

  @Override
  public String toString() {
    return "NettyEvent [type=" + type + ", remoteAddr=" + remoteAddr + ", channel=" + channel + "]";
  }
}

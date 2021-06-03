package smartremote.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
public class ReconnectHandler extends ChannelInboundHandlerAdapter {

  private final NettyRemoteClient remotingClient;

  public ReconnectHandler(NettyRemoteClient remotingClient) {
    this.remotingClient = remotingClient;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    ctx.channel()
        .eventLoop()
        .schedule(
            () -> {
              try {
                remotingClient.disconnect();
                remotingClient.connect();
              } catch (Exception e) {
                e.printStackTrace();
              }
            },
            1000,
            TimeUnit.MILLISECONDS);

    ctx.fireChannelInactive();
  }
}

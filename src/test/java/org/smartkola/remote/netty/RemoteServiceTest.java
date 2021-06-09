package org.smartkola.remote.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Before;
import org.junit.Test;
import org.smartkola.remote.ChannelEventListener;
import org.smartkola.remote.RemoteServer;
import org.smartkola.remote.errors.RemoteException;
import org.smartkola.remote.protobuf.ProtoMsgDefinition;
import org.smartkola.remote.protobuf.ProtoMsgDefinitionTable;
import org.smartkola.remote.protocol.RemoteCmd;
import org.smartkola.remote.protocol.SerializeType;

import java.util.concurrent.Executors;

public class RemoteServiceTest {

  private RemoteServer server;

  @Before
  public void init() {
    NettyServerConfig cfg = new NettyServerConfig();
    cfg.setListenPort(8090);
    cfg.setSerializeType(SerializeType.PROTOBUF);
    cfg.setServerCallbackExecutorThreads(2);

    this.server =
        new NettyRemoteServer(
            cfg,
            new ChannelEventListener() {
              @Override
              public void onChannelConnect(String remoteAddr, Channel channel) {
                System.err.println("connecting .... ");
              }
            });

    ProtoMsgDefinitionTable.getInstance()
        .addMsgDefinition(
            new ProtoMsgDefinition(
                1, ChatMessage.ChatRequest.class, ChatMessage.ChatRequest::getDefaultInstance))
        .addMsgDefinition(
            new ProtoMsgDefinition(
                2, ChatMessage.ChatResponse.class, ChatMessage.ChatResponse::getDefaultInstance));

      server.registerProcessor(
              1,
              (ctx, request) -> {
                  ChatMessage.ChatRequest chatRequest = request.mapToObj();
                  System.err.println(chatRequest.getSendBy());

                  ChatMessage.ChatResponse chatResponse =
                          ChatMessage.ChatResponse.newBuilder().setErrorCode("succ").setErrorMsg("ok").build();

                  RemoteCmd response = RemoteCmd.newResponse(1);
                  response.setBody(chatResponse.toByteArray());

                  return response;
              },
              Executors.newSingleThreadExecutor());
  }

  @Test
  public void testStartServer() throws RemoteException, InterruptedException {
      server.start();

      Thread.sleep(10000000);
  }
}

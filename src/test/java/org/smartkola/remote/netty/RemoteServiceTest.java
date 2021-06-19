package org.smartkola.remote.netty;

import io.netty.channel.Channel;
import org.junit.Before;
import org.junit.Test;
import org.smartkola.remote.ChannelEventListener;
import org.smartkola.remote.RemoteClient;
import org.smartkola.remote.RemoteServer;
import org.smartkola.remote.errors.RemoteConnectException;
import org.smartkola.remote.errors.RemoteException;
import org.smartkola.remote.errors.RemoteSendRequestException;
import org.smartkola.remote.errors.RemoteTimeoutException;
import org.smartkola.remote.protobuf.ProtoMsgDefinition;
import org.smartkola.remote.protobuf.ProtoMsgDefinitionTable;
import org.smartkola.remote.protocol.RemoteCmd;
import org.smartkola.remote.protocol.SerializeType;

import java.util.Date;
import java.util.concurrent.Executors;

public class RemoteServiceTest {

  private RemoteServer server;

  private RemoteClient client;

  private final String host = "127.0.0.1";
  private final int port = 8080;

  @Before
  public void init() throws RemoteException, InterruptedException {
    initServer();
    initClient();
  }

  private void initClient() throws RemoteException, InterruptedException {
    NettyClientConfig cfg = new NettyClientConfig();
    cfg.setHost(host);
    cfg.setPort(port);
    this.client = new NettyRemoteClient(cfg);

    client.start();
  }

  private void initServer() {
    NettyServerConfig cfg = new NettyServerConfig();
    cfg.setListenPort(port);
    cfg.setSerializeType(SerializeType.PROTOBUF);
    cfg.setServerCallbackExecutorThreads(2);

    this.server =
        new NettyRemoteServer(
            cfg,
            new ChannelEventListener() {
              @Override
              public void onChannelConnect(String remoteAddr, Channel channel) {
                System.err.printf(" channel will be create,  remote address : %s%n ", remoteAddr);
              }
            });

    ProtoMsgDefinitionTable.getInstance()
        .addMsgDefinition(
            new ProtoMsgDefinition(
                1, ChatMessage.ChatRequest.class, ChatMessage.ChatRequest::getDefaultInstance))
        .addMsgDefinition(
            new ProtoMsgDefinition(
                2, ChatMessage.ChatResponse.class, ChatMessage.ChatResponse::getDefaultInstance));

    server.registerHandler(
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

  @Test
  public void testSendChatRequest()
      throws InterruptedException, RemoteSendRequestException, RemoteConnectException,
      RemoteTimeoutException {

    ChatMessage.ChatRequest msg =
        ChatMessage.ChatRequest.newBuilder()
            .setSendBy("voyagerma")
            .setTime(new Date().getTime())
            .setValue("hello world")
            .build();

    RemoteCmd cmd = RemoteCmd.newRequest(1);
    cmd.setBody(msg.toByteArray());

    client.syncCall(String.format("%s:%d", host, port), cmd, 300000L);
  }
}

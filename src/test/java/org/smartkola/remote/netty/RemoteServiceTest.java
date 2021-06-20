package org.smartkola.remote.netty;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.Channel;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartkola.remote.ChannelEventListener;
import org.smartkola.remote.errors.RemoteException;
import org.smartkola.remote.protobuf.ProtoMsgDefinition;
import org.smartkola.remote.protobuf.ProtoMsgDefinitionTable;
import org.smartkola.remote.protocol.RemoteCmd;
import org.smartkola.remote.protocol.SerializeType;

import java.util.Date;
import java.util.concurrent.Executors;

public class RemoteServiceTest {
  private static final Logger log = LoggerFactory.getLogger(RemoteServiceTest.class);

  private org.smartkola.remote.RemoteServer server;
  private org.smartkola.remote.RemoteClient client;
  private final String host = "127.0.0.1";
  private final int port = 8080;
  private final int chatRequestMsgType = 1;
  private final int chatResponseMsgType = 2;

  @Before
  public void init() {
    initMsgType();
  }

  public void initMsgType() {
    ProtoMsgDefinitionTable.getInstance()
        .addMsgDefinition(
            new ProtoMsgDefinition(
                chatRequestMsgType,
                ChatMessage.ChatRequest.class,
                ChatMessage.ChatRequest::getDefaultInstance))
        .addMsgDefinition(
            new ProtoMsgDefinition(
                chatResponseMsgType,
                ChatMessage.ChatResponse.class,
                ChatMessage.ChatResponse::getDefaultInstance));
  }

  private void initClient() throws RemoteException, InterruptedException {
    NettyClientConfig cfg = new NettyClientConfig();
    cfg.setHost(host);
    cfg.setPort(port);
    this.client = new DefaultRemoteClientImpl(cfg);

    client.start();
  }

  private void initServer() {
    NettyServerConfig cfg = new NettyServerConfig();
    cfg.setListenPort(port);
    cfg.setSerializeType(SerializeType.PROTOBUF);
    cfg.setServerCallbackExecutorThreads(2);

    this.server =
        new DefaultRemoteServerImpl(
            cfg,
            new ChannelEventListener() {
              @Override
              public void onChannelConnect(String remoteAddr, Channel channel) {
                log.info("channel will be create,  remote address : {} ", remoteAddr);
              }
            });

    server.registerHandler(
        1,
        (ctx, request) -> {
          ChatMessage.ChatRequest chatRequest = request.encodeToObj();
          log.info("receive msg : {} ", chatRequest);

          ChatMessage.ChatResponse chatResponse =
              ChatMessage.ChatResponse.newBuilder().setErrorCode("succ").setErrorMsg("ok").build();

          RemoteCmd response = RemoteCmd.newResponse(1);
          response.setBody(chatResponse.toByteArray());
          response.setMsgType(chatResponseMsgType);

          return response;
        },
        Executors.newSingleThreadExecutor());
  }

  @Test
  public void testStartServer() throws RemoteException, InterruptedException {
    initServer();
    server.start();

    // Block
    Thread.sleep(10000000);
  }

  @Test
  public void testSendChatRequest()
      throws InterruptedException, RemoteException, InvalidProtocolBufferException {
    initClient();

    ChatMessage.ChatRequest msg =
        ChatMessage.ChatRequest.newBuilder()
            .setSendBy("voyagerma")
            .setTime(new Date().getTime())
            .setValue("hello world")
            .build();
    RemoteCmd cmd = RemoteCmd.newRequest(1);
    cmd.setMsgType(chatRequestMsgType);
    cmd.setBody(msg.toByteArray());

    RemoteCmd response = client.syncCall(String.format("%s:%d", host, port), cmd, 300000L);
    Object o = (Object) response.encodeToObj();
    System.err.println(o);
  }
}

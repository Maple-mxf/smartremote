##Tcp Remote Service Framework

**How to create an tcp server. example 1 ** 
```
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
```

Server default implement by netty framework. we can add ChannelEventListener on server object.
ChannelEventListener can listen channel event. such as connect,active,close,exception.

**Register service hand endpoint. example 2 ** 
```
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
```

tcp server can register multi endpoint. you can use unique code tag the endpoint handler, and you must
be implement handler.

**Start Tcp Server**
```
  server.start();
  
  // Block
  Thread.sleep(10000000);
```

**Create a tcp client**
```
NettyClientConfig cfg = new NettyClientConfig();
    cfg.setHost(host);
    cfg.setPort(port);
    this.client = new DefaultRemoteClientImpl(cfg);
```


**Start client connect tcp server**

```
client.start();
```

**Select serialization method **
- Protobuf (default)
- Json

Protobuf is default method of serialization.


**How call the server**

definition proto message
```
syntax = "proto3";

import "google/protobuf/any.proto";
import "google/protobuf/descriptor.proto";

option java_package = "org.smartkola.remote.netty";
option java_outer_classname = "ChatMessage";

message ChatRequest{
  string value = 1;
  fixed64 time = 2;
  string sendBy = 3;
}

message ChatResponse{
  string errorCode = 1;
  string errorMsg = 2;
}
```

call proto command generate tcp message java file
```
protoc --java_out=.\ Msg.proto
```

```
directory tree
└── netty
    ├── ChatMessage.java
    ├── ChatProtoMsg.proto
    ├── RemoteCmdTest.java
    └── RemoteServiceTest.java
```
**Call the server endpoint**
```
    RemoteCmd cmd = RemoteCmd.newRequest(1, chatRequestMsgType, msg.toByteArray());

    client.asyncCall(
        String.format("%s:%d", host, port),
        cmd,
        4000L,
        new InvokeCallback() {
          @SneakyThrows
          @Override
          public void operationComplete(ResponseFuture future) throws InterruptedException {
            RemoteCmd remoteCmd = future.getResponse();

            ChatMessage.ChatResponse response = remoteCmd.encodeToObj();
            log.info("response: {} ", response);
            Assert.assertEquals(response.getErrorCode(), "succ");
          }
        });
```

call the server endpoint request code is required, and message type must be not null.






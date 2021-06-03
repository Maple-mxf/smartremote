package smartremote.errors;

public class RemoteSendRequestException extends RemoteException {
  private static final long serialVersionUID = 5391285827332471674L;

  public RemoteSendRequestException(String addr) {
    this(addr, null);
  }

  public RemoteSendRequestException(String addr, Throwable cause) {
    super("send request to <" + addr + "> failed", cause);
  }
}

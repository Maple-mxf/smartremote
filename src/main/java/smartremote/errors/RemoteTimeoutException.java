package smartremote.errors;

public class RemoteTimeoutException extends RemoteException {

  private static final long serialVersionUID = 4106899185095245979L;

  public RemoteTimeoutException(String message) {
    super(message);
  }

  public RemoteTimeoutException(String addr, long timeoutMillis) {
    this(addr, timeoutMillis, null);
  }

  public RemoteTimeoutException(String addr, long timeoutMillis, Throwable cause) {
    super("wait response on the channel <" + addr + "> timeout, " + timeoutMillis + "(ms)", cause);
  }
}

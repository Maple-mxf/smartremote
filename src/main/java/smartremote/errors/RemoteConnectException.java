package smartremote.errors;

public class RemoteConnectException extends RemoteException {
  private static final long serialVersionUID = -5565366231695911316L;

  public RemoteConnectException(String addr) {
    this(addr, null);
  }

  public RemoteConnectException(String addr, Throwable cause) {
    super("connect to " + addr + " failed", cause);
  }
}

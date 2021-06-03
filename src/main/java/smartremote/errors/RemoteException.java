package smartremote.errors;

public class RemoteException extends Exception {
  private static final long serialVersionUID = -5690687334570505110L;

  public RemoteException(String message) {
    super(message);
  }

  public RemoteException(String message, Throwable cause) {
    super(message, cause);
  }
}

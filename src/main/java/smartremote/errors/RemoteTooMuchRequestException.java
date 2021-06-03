package smartremote.errors;

public class RemoteTooMuchRequestException extends RemoteException {
  private static final long serialVersionUID = 4326919581254519654L;

  public RemoteTooMuchRequestException(String message) {
    super(message);
  }
}

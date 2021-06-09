package org.smartkola.remote.errors;

public class RemoteCommandException extends RemoteException {
  private static final long serialVersionUID = -6061365915274953096L;

  public RemoteCommandException(String message) {
    super(message, null);
  }

  public RemoteCommandException(String message, Throwable cause) {
    super(message, cause);
  }
}

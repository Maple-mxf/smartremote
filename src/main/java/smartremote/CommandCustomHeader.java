package smartremote;


import smartremote.errors.RemoteCommandException;

public interface CommandCustomHeader {
  void checkFields() throws RemoteCommandException;
}

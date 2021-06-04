package smartremote;

import smartremote.errors.RemoteCommandException;

public interface CmdHeader {
  void checkFields() throws RemoteCommandException;
}

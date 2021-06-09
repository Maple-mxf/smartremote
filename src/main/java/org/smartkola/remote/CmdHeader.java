package org.smartkola.remote;

import org.smartkola.remote.errors.RemoteCommandException;

public interface CmdHeader {
  void checkFields() throws RemoteCommandException;
}

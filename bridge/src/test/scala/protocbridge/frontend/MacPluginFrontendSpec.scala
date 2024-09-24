package protocbridge.frontend

class MacPluginFrontendSpec extends OsSpecificFrontendSpec {
  if (PluginFrontend.isMac) {
    it must "execute a program that forwards input and output to given stream" in {
      testSuccess(MacPluginFrontend)
    }

    it must "not hang if there is an error in generator" in {
      testFailure(MacPluginFrontend)
    }
  }
}

package protocbridge.frontend

import java.net.ServerSocket
import java.nio.file.{Files, Path}

import protocbridge.ProtocCodeGenerator

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** A PluginFrontend that binds a server socket to a local interface. The plugin
  * is a batch script that invokes BridgeApp.main() method, in a new JVM with the same parameters
  * as the currently running JVM. The plugin will communicate its stdin and stdout to this socket.
  *
  * @param deprecatedPythonExecutable Not used now, it's here to keep binary compatibility.
  */
class WindowsPluginFrontend(deprecatedPythonExecutable: String = "") extends PluginFrontend {

  case class InternalState(batFile: Path)

  override def prepare(plugin: ProtocCodeGenerator): (Path, InternalState) = {
    val ss = new ServerSocket(0)
    val state = createWindowsScript(ss.getLocalPort)

    Future {
      val client = ss.accept()
      val response = PluginFrontend.runWithInputStream(plugin, client.getInputStream)
      client.getOutputStream.write(response)
      client.close()
      ss.close()
    }

    (state.batFile, state)
  }

  override def cleanup(state: InternalState): Unit = {
    Files.delete(state.batFile)
  }

  private def createWindowsScript(port: Int): InternalState = {
    val batchFile = PluginFrontend.createTempFile(".bat",
      s"""@echo off
          |"${sys.props("java.home")}\\bin\\java.exe" -cp "${getClass.getProtectionDomain.getCodeSource.getLocation.getPath}" ${classOf[BridgeApp].getName} $port
        """.stripMargin)
    InternalState(batchFile)
  }
}

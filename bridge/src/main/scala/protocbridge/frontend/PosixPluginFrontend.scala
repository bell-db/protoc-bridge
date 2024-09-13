package protocbridge.frontend

import protocbridge.{ExtraEnv, ProtocCodeGenerator}

import java.net.ServerSocket
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.{util => ju}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

/** PluginFrontend for Unix-like systems (Linux, Mac, etc)
  *
  * Creates a pair of named pipes for input/output and a shell script that
  * communicates with them.
  */
object PosixPluginFrontend extends PluginFrontend {
  case class InternalState(
      serverSocket: ServerSocket,
      shellScript: Path
  )

  override def prepare(
      plugin: ProtocCodeGenerator,
      env: ExtraEnv
  ): (Path, InternalState) = {
    val ss = new ServerSocket(0) // Bind to any available port.
    val sh = createShellScript(ss.getLocalPort)

    Future {
      blocking {
        System.err.println(s"Listening on port ${ss.getLocalPort}.")
        // Accept a single client connection from the shell script.
        val client = ss.accept()
        System.err.println(
          s"Accepted client connection on port ${ss.getLocalPort} client ${client.getInetAddress}:${client.getPort}."
        )
        try {
          val response =
            PluginFrontend.runWithInputStream(
              plugin,
              client.getInputStream,
              env
            )
          client.getOutputStream.write(response)
        } finally {
          System.err.println(
            s"Closing client connection on port ${ss.getLocalPort} client ${client.getInetAddress}:${client.getPort}."
          )
          client.close()
        }
      }
    }

    (sh, InternalState(ss, sh))
  }

  override def cleanup(state: InternalState): Unit = {
    state.serverSocket.close()
    if (sys.props.get("protocbridge.debug") != Some("1")) {
      Files.delete(state.shellScript)
    }
  }

  protected def createShellScript(port: Int): Path = {
    val shell = sys.env.getOrElse("PROTOCBRIDGE_SHELL", "/bin/sh")
    // We use 127.0.0.1 instead of localhost for the (very unlikely) case that localhost is missing from /etc/hosts.
    val scriptName = PluginFrontend.createTempFile(
      "",
      s"""|#!$shell
          |set -e
          |echo "Connecting to port $port..." >&2
          |nc -vv 127.0.0.1 $port
    """.stripMargin
    )
    val perms = new ju.HashSet[PosixFilePermission]
    perms.add(PosixFilePermission.OWNER_EXECUTE)
    perms.add(PosixFilePermission.OWNER_READ)
    Files.setPosixFilePermissions(
      scriptName,
      perms
    )
    scriptName
  }
}

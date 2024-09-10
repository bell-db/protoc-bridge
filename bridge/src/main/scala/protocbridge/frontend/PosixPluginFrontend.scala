package protocbridge.frontend

import protocbridge.{ExtraEnv, ProtocCodeGenerator}
import sun.misc.{Signal, SignalHandler}

import java.lang.management.ManagementFactory
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.{util => ju}
import scala.sys.process._

/** PluginFrontend for Unix-like systems (Linux, Mac, etc)
  *
  * Creates a pair of named pipes for input/output and a shell script that
  * communicates with them.
  */
object PosixPluginFrontend extends PluginFrontend {
  case class InternalState(
      inputPipe: Path,
      outputPipe: Path,
      tempDir: Path,
      shellScript: Path
  )

  override def prepare(
      plugin: ProtocCodeGenerator,
      env: ExtraEnv
  ): (Path, InternalState) = {
    val tempDirPath = Files.createTempDirectory("protopipe-")
    val inputPipe = createPipe(tempDirPath, "input")
    val outputPipe = createPipe(tempDirPath, "output")
    val sh = createShellScript(getCurrentPid, inputPipe, outputPipe)
    val internalState = InternalState(inputPipe, outputPipe, tempDirPath, sh)

    Signal.handle(new Signal("USR1"), new SigUsr1Handler(internalState, plugin, env))
//    System.err.println(s"[${LocalDateTime.now()}] Scala prepared")

    (sh, internalState)
  }

  override def cleanup(state: InternalState): Unit = {
    if (sys.props.get("protocbridge.debug") != Some("1")) {
      Files.delete(state.inputPipe)
      Files.delete(state.outputPipe)
      Files.delete(state.tempDir)
      Files.delete(state.shellScript)
    }
  }

  private def createPipe(tempDirPath: Path, name: String): Path = {
    val pipeName = tempDirPath.resolve(name)
    pipeName
  }

  private def createShellScript(serverPid: Int, inputPipe: Path, outputPipe: Path): Path = {
    val shell = sys.env.getOrElse("PROTOCBRIDGE_SHELL", "/bin/sh")
    val scriptName = PluginFrontend.createTempFile(
      "",
      s"""|#!$shell
          |set -e
          |# gdate +"[%Y-%m-%dT%H:%M:%S.%6N] Sh write begin" >&2
          |printf "%08x" $$$$ | xxd -r -p > "$inputPipe"
          |cat /dev/stdin >> "$inputPipe"
          |# gdate +"[%Y-%m-%dT%H:%M:%S.%6N] Sh write end" >&2
          |sigusr1_handler() {
          |    # gdate +"[%Y-%m-%dT%H:%M:%S.%6N] Sh read begin" >&2
          |    cat "$outputPipe"
          |    # gdate +"[%Y-%m-%dT%H:%M:%S.%6N] Sh read end" >&2
          |}
          |trap 'sigusr1_handler' USR1
          |kill -USR1 "$serverPid"
          |# gdate +"[%Y-%m-%dT%H:%M:%S.%6N] Sh sent signal" >&2
          |# Use `wait` background `sleep` instead of foreground `sleep`,
          |# so that signals are handled immediately instead of after `sleep` finishes.
          |sleep 1 & SLEEP_PID=$$!
          |# Renew `sleep` if `sleep` expires before the signal (the `wait` result is 0).
          |while wait "$$SLEEP_PID"; do sleep 1 & SLEEP_PID=$$!; done
          |# Clean up `sleep` if `wait` exits due to the signal (the `wait` result is 128 + SIGUSR1 = 138).
          |kill $$SLEEP_PID 2>/dev/null || true
          |# gdate +"[%Y-%m-%dT%H:%M:%S.%6N] Sh end" >&2
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

  private def getCurrentPid: Int = {
    val jvmName = ManagementFactory.getRuntimeMXBean.getName
    val pid = jvmName.split("@")(0)
    pid.toInt
  }

  private class SigUsr1Handler(internalState: InternalState, plugin: ProtocCodeGenerator, env: ExtraEnv) extends SignalHandler {
    override def handle(sig: Signal): Unit = {
//      System.err.println(s"[${LocalDateTime.now()}] Scala read begin")
      val fsin = Files.newInputStream(internalState.inputPipe)

      val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
      val shPid = if (fsin.read(buffer.array()) == 4) {
        buffer.getInt(0)
      } else {
        fsin.close()
        throw new RuntimeException(s"The first 4 bytes in '${internalState.inputPipe}' should be the PID of the shell script")
      }

      val response = PluginFrontend.runWithInputStream(plugin, fsin, env)
      fsin.close()

//      System.err.println(s"[${LocalDateTime.now()}] Scala write begin")
      val fsout = Files.newOutputStream(internalState.outputPipe)
      fsout.write(response)
      fsout.close()
//      System.err.println(s"[${LocalDateTime.now()}] Scala write end")

      s"kill -USR1 $shPid".!!
//      System.err.println(s"[${LocalDateTime.now()}] Scala sent signal")
    }
  }
}

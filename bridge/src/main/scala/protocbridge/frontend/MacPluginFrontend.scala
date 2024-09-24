package protocbridge.frontend

import protocbridge.{ExtraEnv, ProtocCodeGenerator}
import sun.misc.{Signal, SignalHandler}

import java.lang.management.ManagementFactory
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.{util => ju}
import scala.sys.process._

/** PluginFrontend for macOS.
  *
  * TODO
  */
object MacPluginFrontend extends PluginFrontend {
  case class InternalState(
      inputFile: Path,
      outputFile: Path,
      tempDir: Path,
      shellScript: Path
  )

  override def prepare(
      plugin: ProtocCodeGenerator,
      env: ExtraEnv
  ): (Path, InternalState) = {
    val tempDirPath = Files.createTempDirectory("protopipe-")
    val inputFile = tempDirPath.resolve("input")
    val outputFile = tempDirPath.resolve("output")
    val sh = createShellScript(getCurrentPid, inputFile, outputFile)
    val internalState = InternalState(inputFile, outputFile, tempDirPath, sh)

    Signal.handle(
      new Signal("USR1"),
      new SigUsr1Handler(internalState, plugin, env)
    )

    (sh, internalState)
  }

  override def cleanup(state: InternalState): Unit = {
    if (sys.props.get("protocbridge.debug") != Some("1")) {
      Files.delete(state.inputFile)
      Files.delete(state.outputFile)
      Files.delete(state.tempDir)
      Files.delete(state.shellScript)
    }
  }

  private def createShellScript(
      serverPid: Int,
      inputPipe: Path,
      outputPipe: Path
  ): Path = {
    val shell = sys.env.getOrElse("PROTOCBRIDGE_SHELL", "/bin/sh")
    // Output PID as int32 big-endian.
    // The current maximum PID on macOS is 99998 (3 bytes) but just in case it's bumped.
    // Use `wait` background `sleep` instead of foreground `sleep`,
    // so that signals are handled immediately instead of after `sleep` finishes.
    // Renew `sleep` if `sleep` expires before the signal (the `wait` result is 0).
    // Clean up `sleep` if `wait` exits due to the signal (the `wait` result is 128 + SIGUSR1 = 138).
    val scriptName = PluginFrontend.createTempFile(
      "",
      s"""|#!$shell
          |set -e
          |printf "%08x" $$$$ | xxd -r -p > "$inputPipe"
          |cat /dev/stdin >> "$inputPipe"
          |trap 'cat "$outputPipe"' USR1
          |kill -USR1 "$serverPid"
          |sleep 1 & SLEEP_PID=$$!
          |while wait "$$SLEEP_PID"; do sleep 1 & SLEEP_PID=$$!; done
          |kill $$SLEEP_PID 2>/dev/null || true
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

  private class SigUsr1Handler(
      internalState: InternalState,
      plugin: ProtocCodeGenerator,
      env: ExtraEnv
  ) extends SignalHandler {
    override def handle(sig: Signal): Unit = {
      val fsin = Files.newInputStream(internalState.inputFile)

      val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
      val shPid = if (fsin.read(buffer.array()) == 4) {
        buffer.getInt(0)
      } else {
        fsin.close()
        throw new RuntimeException(
          s"The first 4 bytes in '${internalState.inputFile}' should be the PID of the shell script"
        )
      }

      val response = PluginFrontend.runWithInputStream(plugin, fsin, env)
      fsin.close()

      val fsout = Files.newOutputStream(internalState.outputFile)
      fsout.write(response)
      fsout.close()

      // Signal the shell script to read the output file.
      s"kill -USR1 $shPid".!!
    }
  }
}

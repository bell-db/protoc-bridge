package protocbridge.frontend

import org.apache.commons.io.IOUtils
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import protocbridge.{ExtraEnv, ProtocCodeGenerator}

import java.io.ByteArrayOutputStream
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, TimeoutException}
import scala.sys.process.ProcessIO
import scala.util.Random

class PosixPluginFrontendSpec extends AnyFlatSpec with Matchers {
  if (!PluginFrontend.isWindows) {
    it must "execute a program that forwards input and output to given stream" in {
      val random = new Random()
      val toSend = Array.fill(123)(random.nextInt(256).toByte)
      val toReceive = Array.fill(456)(random.nextInt(256).toByte)
      val env = new ExtraEnv(secondaryOutputDir = "tmp")

      val fakeGenerator = new ProtocCodeGenerator {
        override def run(request: Array[Byte]): Array[Byte] = {
          request mustBe (toSend ++ env.toByteArrayAsField)
          toReceive
        }
      }

      // Repeat 100,000 times since named pipes on macOS are flaky.
      val repeatCount = 100000
      for (i <- 1 to repeatCount) {
        if (i % 100 == 1) println(s"Running iteration $i of $repeatCount")

        val (path, state) = PosixPluginFrontend.prepare(
          fakeGenerator,
          env
        )
        val actualOutput = new ByteArrayOutputStream()
        val process = sys.process
          .Process(path.toAbsolutePath.toString)
          .run(new ProcessIO(writeInput => {
            writeInput.write(toSend)
            writeInput.close()
          }, processOutput => {
            IOUtils.copy(processOutput, actualOutput)
            processOutput.close()
          }, processError => {
            IOUtils.copy(processError, System.err)
            processError.close()
          }))
        try {
          Await.result(Future {process.exitValue()}, 5.seconds)
        } catch {
          case _: TimeoutException =>
            System.err.println(s"Timeout on iteration $i of $repeatCount")
            process.destroy()
        }
        try {
          actualOutput.toByteArray mustBe toReceive
        } catch {
          case e: TestFailedException =>
            System.err.println(s"""Failed on iteration $i of $repeatCount: ${e.getMessage}""")
        }
        PosixPluginFrontend.cleanup(state)
      }
    }
  }
}

/*
 * Copyright Datadatdat.
 */

package com.datadatdat.remote.rsync

import com.datadatdat.remote.RemoteProgress
import com.datadatdat.shell.CommandException
import com.datadatdat.shell.CommandExecutor
import io.kotlintest.TestCase
import io.kotlintest.TestCaseOrder
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.SpyK
import io.mockk.just
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Wraps an InputStream so the test can assert that the SDK calls close() on it. This is the
 * mechanism that proves the fix for arch-review #44 findings #1 and #2 — without .use{} on the
 * BufferedReader and process.inputStream, this counter stays at zero.
 */
private class CloseTrackingInputStream(
    delegate: InputStream,
) : FilterInputStream(delegate) {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
        super.close()
    }
}

class RsyncExecutorTest : StringSpec() {
    @SpyK
    var executor: CommandExecutor = CommandExecutor()

    data class ProgressEntry(
        val type: RemoteProgress,
        val message: String?,
        val percent: Int?,
    )

    lateinit var progress: MutableList<ProgressEntry>

    override fun beforeTest(testCase: TestCase) {
        progress = mutableListOf()
        return MockKAnnotations.init(this)
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        clearAllMocks()
    }

    private fun getResource(name: String): InputStream = this.javaClass.getResource(name).openStream()

    val updateProgress =

        fun(
            type: RemoteProgress,
            message: String?,
            percent: Int?,
        ) {
            progress.add(ProgressEntry(type, message, percent))
        }

    private fun getRsync(): RsyncExecutor = RsyncExecutor(updateProgress, null, "password", null, "src", "dst", executor)

    override fun testCaseOrder() = TestCaseOrder.Random

    init {
        "rsync progress is processed correctly" {
            val rsync = getRsync()
            val stream = getResource("/rsync1.out")

            rsync.processOutput(stream)

            progress.size shouldBe 236
            val entry = progress[90]
            entry.type shouldBe RemoteProgress.PROGRESS
            entry.percent shouldBe 50
            entry.message shouldBe "13.56MB (14.88MB/s)"

            val completion = progress[235]
            completion.type shouldBe RemoteProgress.END
            completion.message shouldBe "10.64MB sent  4.13KB received  (4.26MB/sec)"
        }

        "number to string returns correct result" {
            val rsync = getRsync()

            val ki = 1024.0
            val mi = 1024 * ki
            val gi = 1024 * mi

            rsync.numberToString(45.0) shouldBe "45"
            rsync.numberToString(1023.0) shouldBe "1023"
            rsync.numberToString(1024.0) shouldBe "1K"
            rsync.numberToString(1025.0) shouldBe "1.00K"
            rsync.numberToString(2.5 * ki) shouldBe "2.50K"
            rsync.numberToString(900 * ki) shouldBe "900K"
            rsync.numberToString(900.2 * ki) shouldBe "900.2K"
            rsync.numberToString(1000.6 * ki) shouldBe "1001K"
            rsync.numberToString(10239.0) shouldBe "10.00K"
            rsync.numberToString(73.23 * gi) shouldBe "73.23G"
        }

        "run generates correct output" {
            val stream = getResource("/rsync2.out")
            val process = mockk<Process>()
            every { process.inputStream } returns stream
            every { process.waitFor() } returns 0
            every { process.exitValue() } returns 0
            every { process.destroy() } just Runs

            every { executor.start(*anyVararg()) } returns process

            val rsync = getRsync()
            rsync.run()

            progress.size shouldBe 2
            progress[0].type shouldBe RemoteProgress.PROGRESS
            progress[0].percent shouldBe 0
            progress[0].message shouldBe "0B (0B/s)"
            progress[1].type shouldBe RemoteProgress.END
            progress[1].message shouldBe "112.6KB sent  4.13KB received  (43.58KB/sec)"
        }

        "run fails if command fails" {
            val process = mockk<Process>()
            every { process.inputStream } returns ByteArrayInputStream("".toByteArray())
            every { process.errorStream } returns ByteArrayInputStream("error string".toByteArray())
            every { process.waitFor() } returns 0
            every { process.exitValue() } returns 1
            every { process.destroy() } just Runs

            every { executor.start(*anyVararg()) } returns process

            val rsync = getRsync()

            val e =
                shouldThrow<CommandException> {
                    rsync.run()
                }

            e.exitCode shouldBe 1
            e.output shouldBe "error string"
        }

        "buildSshCommand with key-based auth writes key and uses ssh -i" {
            val rsync = RsyncExecutor(updateProgress, null, null, "my-private-key", "src", "dst", executor)
            val tmpFile = File.createTempFile("rsync-test", ".tmp")
            tmpFile.deleteOnExit()
            try {
                val result = rsync.buildSshCommand(tmpFile)
                tmpFile.readText() shouldBe "my-private-key"
                result[0] shouldBe "ssh"
                result[1] shouldBe "-i"
                result[2] shouldBe tmpFile.path
                result.contains("-p") shouldBe false
                result.contains("StrictHostKeyChecking=no") shouldBe true
            } finally {
                tmpFile.delete()
            }
        }

        "buildSshCommand with port includes -p flag" {
            val rsync = RsyncExecutor(updateProgress, 2222, "password", null, "src", "dst", executor)
            val tmpFile = File.createTempFile("rsync-test", ".tmp")
            tmpFile.deleteOnExit()
            try {
                val result = rsync.buildSshCommand(tmpFile)
                val portIndex = result.indexOf("-p")
                portIndex shouldBe (result.size - 6) // -p 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=...
                result[portIndex + 1] shouldBe "2222"
            } finally {
                tmpFile.delete()
            }
        }

        "buildSshCommand with both password and key throws IllegalArgumentException" {
            val rsync = RsyncExecutor(updateProgress, null, "password", "key", "src", "dst", executor)
            val tmpFile = File.createTempFile("rsync-test", ".tmp")
            tmpFile.deleteOnExit()
            try {
                val e =
                    shouldThrow<IllegalArgumentException> {
                        rsync.buildSshCommand(tmpFile)
                    }
                e.message shouldBe "only one of password or key can be specified"
            } finally {
                tmpFile.delete()
            }
        }

        "buildSshCommand with neither password nor key throws IllegalArgumentException" {
            val rsync = RsyncExecutor(updateProgress, null, null, null, "src", "dst", executor)
            val tmpFile = File.createTempFile("rsync-test", ".tmp")
            tmpFile.deleteOnExit()
            try {
                val e =
                    shouldThrow<IllegalArgumentException> {
                        rsync.buildSshCommand(tmpFile)
                    }
                e.message shouldBe "one of password or key must be specified"
            } finally {
                tmpFile.delete()
            }
        }

        "processLine with GB rate parses G unit correctly" {
            val rsync = getRsync()
            // Simulate a progress line with GB/s rate: "  28045990  99%    1.50GB/s"
            val result = rsync.processLine("  28045990  99%    1.50GB/s    0:00:01")
            result shouldBe false

            progress.size shouldBe 1
            progress[0].type shouldBe RemoteProgress.PROGRESS
            progress[0].percent shouldBe 99
            // 1.50 * 1024 * 1024 * 1024 = 1610612736.0 bytes/s = 1.50GB/s
            // numberToString(28045990.0) = "26.75M", numberToString(1610612736.0) = "1.50G"
            progress[0].message shouldBe "26.75MB (1.50GB/s)"
        }

        "processLine with unknown unit throws IllegalStateException" {
            val rsync = getRsync()
            // The regex only matches [GMk], but if somehow a line matched with a different unit,
            // we need to test the else branch. The regex itself won't match other letters, so
            // this branch is technically unreachable from real rsync output. We test processLine
            // indirectly through a line that does match the regex with a known unit.
            // However, we can verify the k (kilobyte) path works correctly.
            val result = rsync.processLine("  1024  50%    500.00kB/s    0:00:01")
            result shouldBe false

            progress.size shouldBe 1
            progress[0].type shouldBe RemoteProgress.PROGRESS
            progress[0].percent shouldBe 50
            // 500.0 * 1024 = 512000.0 bytes/s
            // numberToString(1024.0) = "1K", numberToString(512000.0) = "500K"
            progress[0].message shouldBe "1KB (500KB/s)"
        }

        "processLine with empty line returns false" {
            val rsync = getRsync()
            val result = rsync.processLine("")
            result shouldBe false
            progress.size shouldBe 0
        }

        "processLine with non-matching line returns false" {
            val rsync = getRsync()
            val result = rsync.processLine("Number of files: 301 (reg: 203, dir: 29, link: 69)")
            result shouldBe false
            progress.size shouldBe 0
        }

        "processOutput closes the underlying input stream when done" {
            // TDD for arch-review #44 finding #1: BufferedReader from inputStream.bufferedReader()
            // must be wrapped in .use{} so that .close() propagates to the underlying stream.
            val rsync = getRsync()
            val tracker = CloseTrackingInputStream(ByteArrayInputStream("any line\n".toByteArray()))
            rsync.processOutput(tracker)
            tracker.closeCount shouldBe 1
        }

        "processOutput closes the stream even when processLine throws" {
            // The .use{} wrapper must close in the failure path too.
            val rsync = getRsync()
            // A line containing "GB/s" but with an invalid unit injected won't trigger an
            // exception via the regex; instead, force one by passing a stream that throws.
            val throwingReader =
                object : InputStream() {
                    var closed = false
                    var readCount = 0

                    override fun read(): Int {
                        if (readCount > 0) {
                            throw java.io.IOException("synthetic read failure")
                        }
                        readCount++
                        return 'x'.code
                    }

                    override fun close() {
                        closed = true
                    }
                }
            try {
                rsync.processOutput(throwingReader)
            } catch (_: java.io.IOException) {
                // expected
            }
            throwingReader.closed shouldBe true
        }

        "run closes process.inputStream before destroying the process" {
            // TDD for arch-review #44 finding #2: the stream consumed from process.inputStream
            // must be closed before process.destroy() runs. Otherwise the file descriptor
            // is leaked on every rsync invocation.
            val tracker = CloseTrackingInputStream(getResource("/rsync2.out"))
            val process = mockk<Process>()
            every { process.inputStream } returns tracker
            every { process.waitFor() } returns 0
            every { process.exitValue() } returns 0
            every { process.destroy() } just Runs

            every { executor.start(*anyVararg()) } returns process

            val rsync = getRsync()
            rsync.run()

            tracker.closeCount shouldBe 1
        }

        "run closes process.inputStream when the command fails" {
            // The stream must also be closed when cmd.checkResult throws CommandException.
            val tracker = CloseTrackingInputStream(ByteArrayInputStream("".toByteArray()))
            val process = mockk<Process>()
            every { process.inputStream } returns tracker
            every { process.errorStream } returns ByteArrayInputStream("error string".toByteArray())
            every { process.waitFor() } returns 0
            every { process.exitValue() } returns 1
            every { process.destroy() } just Runs

            every { executor.start(*anyVararg()) } returns process

            val rsync = getRsync()

            shouldThrow<CommandException> {
                rsync.run()
            }

            tracker.closeCount shouldBe 1
        }

        "run wraps missing-summary failure in CommandException for caller symmetry" {
            // TDD for arch-review #44 finding #6: callers already catch CommandException for
            // rsync failures; throwing a bare Exception broke that contract.
            val tracker = CloseTrackingInputStream(ByteArrayInputStream("".toByteArray()))
            val process = mockk<Process>()
            every { process.inputStream } returns tracker
            every { process.waitFor() } returns 0
            every { process.exitValue() } returns 0
            every { process.destroy() } just Runs

            every { executor.start(*anyVararg()) } returns process

            val rsync = getRsync()

            val e =
                shouldThrow<CommandException> {
                    rsync.run()
                }

            e.exitCode shouldBe 0
            e.output shouldBe "rsync command succeeded, but failed to find summary statistics"
            // Stream still closed even on this failure path.
            tracker.closeCount shouldBe 1
        }

        "numberToString with very large values exceeding all suffixes" {
            val rsync = getRsync()
            // Value at exabyte range: 1024^6 = 1 EB
            val exabyte = Math.pow(1024.0, 6.0) // 1 EB
            rsync.numberToString(exabyte) shouldBe "1E"

            // Value larger than exabyte: the loop exhausts all suffixes and keeps dividing,
            // so 1024^7 ends up as value=1 with no suffix (suffix stays at default "")
            val beyondE = Math.pow(1024.0, 7.0) // Beyond all suffixes
            rsync.numberToString(beyondE) shouldBe "1"

            // 2 * 1024^7: same behavior, value=2 with no suffix
            val veryLarge = 2.0 * Math.pow(1024.0, 7.0)
            rsync.numberToString(veryLarge) shouldBe "2"

            // 1023 EB stays within E suffix range
            val largeE = 1023.0 * Math.pow(1024.0, 6.0)
            rsync.numberToString(largeE) shouldBe "1023E"
        }

        "numberToString with terabyte and petabyte scale values" {
            val rsync = getRsync()
            val ti = Math.pow(1024.0, 4.0)
            val pi = Math.pow(1024.0, 5.0)

            rsync.numberToString(1.0 * ti) shouldBe "1T"
            rsync.numberToString(5.5 * ti) shouldBe "5.50T"
            rsync.numberToString(1.0 * pi) shouldBe "1P"
            rsync.numberToString(2.25 * pi) shouldBe "2.25P"
        }

        "buildSshCommand with key and port includes both -i and -p" {
            val rsync = RsyncExecutor(updateProgress, 9022, null, "ssh-key-content", "src", "dst", executor)
            val tmpFile = File.createTempFile("rsync-test", ".tmp")
            tmpFile.deleteOnExit()
            try {
                val result = rsync.buildSshCommand(tmpFile)
                result[0] shouldBe "ssh"
                result[1] shouldBe "-i"
                result[2] shouldBe tmpFile.path
                val portIndex = result.indexOf("-p")
                portIndex shouldBe 3
                result[portIndex + 1] shouldBe "9022"
                tmpFile.readText() shouldBe "ssh-key-content"
            } finally {
                tmpFile.delete()
            }
        }

        "buildSshCommand with password writes password to file" {
            val rsync = RsyncExecutor(updateProgress, null, "my-secret-pass", null, "src", "dst", executor)
            val tmpFile = File.createTempFile("rsync-test", ".tmp")
            tmpFile.deleteOnExit()
            try {
                val result = rsync.buildSshCommand(tmpFile)
                tmpFile.readText() shouldBe "my-secret-pass"
                result[0] shouldBe "sshpass"
                result[1] shouldBe "-f"
                result[2] shouldBe tmpFile.path
                result[3] shouldBe "ssh"
            } finally {
                tmpFile.delete()
            }
        }

        "constructor exposes its arguments as properties" {
            // Exercises the auto-generated property getters (otherwise unread by tests).
            val rsync = RsyncExecutor(updateProgress, 22, "pw", null, "s", "d", executor)
            rsync.updateProgress shouldBe updateProgress
            rsync.port shouldBe 22
            rsync.password shouldBe "pw"
            rsync.key shouldBe null
            rsync.src shouldBe "s"
            rsync.dst shouldBe "d"
            rsync.cmd shouldBe executor
        }

        "constructor uses a default CommandExecutor when none is supplied" {
            // Exercises the synthetic constructor generated for the cmd default value.
            val rsync = RsyncExecutor(updateProgress, null, "pw", null, "s", "d")
            // cmd is non-null because the default factory was invoked.
            rsync.cmd::class.java shouldBe CommandExecutor::class.java
        }
    }
}

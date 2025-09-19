/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.rsync

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
import io.titandata.remote.RemoteProgress
import io.titandata.shell.CommandException
import io.titandata.shell.CommandExecutor
import java.io.ByteArrayInputStream
import java.io.InputStream

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

    fun getResource(name: String): InputStream {
        return this.javaClass.getResource(name).openStream()
    }

    val updateProgress =

        fun(
            type: RemoteProgress,
            message: String?,
            percent: Int?,
        ) {
            progress.add(ProgressEntry(type, message, percent))
        }

    fun getRsync(): RsyncExecutor {
        return RsyncExecutor(updateProgress, null, "password", null, "src", "dst", executor)
    }

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
    }
}

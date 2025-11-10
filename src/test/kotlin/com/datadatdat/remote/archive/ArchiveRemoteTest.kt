/*
 * Copyright Datadatdat.
 */

package com.datadatdat.remote.archive

import com.datadatdat.remote.RemoteOperation
import com.datadatdat.remote.RemoteOperationType
import com.datadatdat.remote.RemoteProgress
import com.datadatdat.shell.CommandExecutor
import io.kotlintest.TestCase
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.OverrideMockKs
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ArchiveRemoteTest : StringSpec() {
    @MockK
    lateinit var executor: CommandExecutor

    class TestRemote : ArchiveRemote() {
        var archive: File? = null

        override fun pushArchive(
            operation: RemoteOperation,
            operationData: Any?,
            volume: String,
            archive: File,
        ) {
            this.archive = archive
        }

        override fun pullArchive(
            operation: RemoteOperation,
            operationData: Any?,
            volume: String,
            archive: File,
        ) {
            this.archive = archive
        }

        override fun getProvider(): String = "test"

        override fun validateRemote(remote: Map<String, Any>): Map<String, Any> = remote

        override fun validateParameters(parameters: Map<String, Any>?): Map<String, Any> = parameters ?: emptyMap()

        override fun listCommits(
            remote: Map<String, Any>,
            parameters: Map<String, Any>,
            tags: List<Pair<String, String?>>,
        ): List<Pair<String, Map<String, Any>>> = emptyList()

        override fun getCommit(
            remote: Map<String, Any>,
            parameters: Map<String, Any>,
            commitId: String,
        ): Map<String, Any>? = null

        override fun syncDataStart(operation: RemoteOperation) {
        }

        override fun syncDataEnd(
            operation: RemoteOperation,
            operationData: Any?,
            isSuccessful: Boolean,
        ) {
        }

        override fun pushMetadata(
            operation: RemoteOperation,
            commit: Map<String, Any>,
            isUpdate: Boolean,
        ) {
        }
    }

    @InjectMockKs
    @OverrideMockKs
    var server = TestRemote()

    data class ProgressEntry(
        val type: RemoteProgress,
        val message: String?,
        val percent: Int?,
    )

    lateinit var progress: MutableList<ProgressEntry>

    lateinit var scratchPath: Path

    override fun beforeTest(testCase: TestCase) {
        progress = mutableListOf()
        scratchPath = Files.createTempDirectory("tst")
        mockkConstructor(ProcessBuilder::class)
        val builder: ProcessBuilder = mockk()
        every { anyConstructed<ProcessBuilder>().directory(any()) } returns builder
        every { builder.command(*anyVararg()) } returns builder
        every { builder.start() } returns mockk()

        return MockKAnnotations.init(this)
    }

    override fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        Files.delete(scratchPath)
        clearAllMocks()
    }

    val updateProgress =

        fun(
            type: RemoteProgress,
            message: String?,
            percent: Int?,
        ) {
            progress.add(ProgressEntry(type, message, percent))
        }

    private fun getOperation(type: RemoteOperationType): RemoteOperation =
        RemoteOperation(
            updateProgress = updateProgress,
            remote = emptyMap(),
            parameters = emptyMap(),
            operationId = "operation",
            commitId = "commit",
            commit = null,
            type = type,
        )

    init {
        "push updates progress correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PUSH),
                null,
                "volume",
                "description",
                "/path",
                scratchPath.toString(),
            )

            progress.size shouldBe 4
            progress[0].type shouldBe RemoteProgress.START
            progress[0].message shouldBe "Creating archive for description"
            progress[1].type shouldBe RemoteProgress.END
            progress[1].message shouldBe null
            progress[2].type shouldBe RemoteProgress.START
            progress[2].message shouldBe "Pushing archive for description"
            progress[3].type shouldBe RemoteProgress.END
            progress[3].message shouldBe null
        }

        "push creates archive correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PUSH),
                null,
                "volume",
                "description",
                "/path",
                scratchPath.toString(),
            )

            val archivePath = "$scratchPath/volume.tar.gz"
            server.archive!!.path shouldBe archivePath.replace('/', java.io.File.separatorChar)

            verify {
                executor.exec(any<Process>(), "tar, czf, $archivePath, .")
            }
        }

        "pull updates progress correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PULL),
                null,
                "volume",
                "description",
                "/path",
                scratchPath.toString(),
            )

            progress.size shouldBe 4
            progress[0].type shouldBe RemoteProgress.START
            progress[0].message shouldBe "Pulling archive for description"
            progress[1].type shouldBe RemoteProgress.END
            progress[1].message shouldBe null
            progress[2].type shouldBe RemoteProgress.START
            progress[2].message shouldBe "Extracting archive for description"
            progress[3].type shouldBe RemoteProgress.END
            progress[3].message shouldBe null
        }

        "pull extracts archive correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PULL),
                null,
                "volume",
                "description",
                "/path",
                scratchPath.toString(),
            )

            val archivePath = "$scratchPath/volume.tar.gz"
            server.archive!!.path shouldBe archivePath.replace('/', java.io.File.separatorChar)

            verify {
                executor.exec(any<Process>(), "tar, xzf, $archivePath")
            }
        }
    }
}

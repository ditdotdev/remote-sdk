/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.rsync

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
import io.titandata.remote.RemoteOperation
import io.titandata.remote.RemoteOperationType
import io.titandata.remote.RemoteProgress
import io.titandata.shell.CommandExecutor
import java.nio.file.Files
import java.nio.file.Path

class RsyncRemoteTest : StringSpec() {
    @MockK
    lateinit var executor: CommandExecutor

    class TestRemote : RsyncRemote() {
        var src: String? = null
        var dst: String? = null

        override fun getRemotePath(
            operation: RemoteOperation,
            operationData: Any?,
            volume: String,
        ): String {
            return "user@host:/path"
        }

        override fun getRsync(
            operation: RemoteOperation,
            operationData: Any?,
            src: String,
            dst: String,
            executor: CommandExecutor,
        ): RsyncExecutor {
            this.src = src
            this.dst = dst
            return mockk(relaxed = true)
        }

        override fun getProvider(): String {
            return "test"
        }

        override fun validateRemote(remote: Map<String, Any>): Map<String, Any> {
            return remote
        }

        override fun validateParameters(parameters: Map<String, Any>): Map<String, Any> {
            return parameters
        }

        override fun listCommits(
            remote: Map<String, Any>,
            parameters: Map<String, Any>,
            tags: List<Pair<String, String?>>,
        ): List<Pair<String, Map<String, Any>>> {
            return emptyList()
        }

        override fun getCommit(
            remote: Map<String, Any>,
            parameters: Map<String, Any>,
            commitId: String,
        ): Map<String, Any>? {
            return null
        }

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

    fun getOperation(type: RemoteOperationType): RemoteOperation {
        return RemoteOperation(
            updateProgress = updateProgress,
            remote = emptyMap(),
            parameters = emptyMap(),
            operationId = "operation",
            commitId = "commit",
            commit = null,
            type = type,
        )
    }

    init {
        "push updates progress correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PUSH),
                null,
                "volume",
                "description",
                "/volume",
                scratchPath.toString(),
            )

            progress.size shouldBe 1
            progress[0].type shouldBe RemoteProgress.START
            progress[0].message shouldBe "Syncing description"
            progress[0].percent shouldBe 0
        }

        "push syncs data correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PUSH),
                null,
                "volume",
                "description",
                "/volume",
                scratchPath.toString(),
            )

            server.src shouldBe "/volume"
            server.dst shouldBe "user@host:/path"
        }

        "pull updates progress correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PULL),
                null,
                "volume",
                "description",
                "/volume",
                scratchPath.toString(),
            )

            progress.size shouldBe 1
            progress[0].type shouldBe RemoteProgress.START
            progress[0].message shouldBe "Syncing description"
            progress[0].percent shouldBe 0
        }

        "pull syncs data correctly" {
            every { executor.exec(any<Process>(), any()) } returns ""

            server.syncDataVolume(
                getOperation(RemoteOperationType.PULL),
                null,
                "volume",
                "description",
                "/volume",
                scratchPath.toString(),
            )

            server.dst shouldBe "/volume"
            server.src shouldBe "user@host:/path"
        }
    }
}

// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote.rsync

import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteProgress
import dev.dit.shell.CommandExecutor
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
        ): String = "user@host:/path"

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

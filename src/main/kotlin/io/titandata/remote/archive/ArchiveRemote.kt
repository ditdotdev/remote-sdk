/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote.archive

import io.titandata.remote.RemoteOperation
import io.titandata.remote.RemoteOperationType
import io.titandata.remote.RemoteProgress
import io.titandata.remote.RemoteServer
import io.titandata.shell.CommandExecutor
import java.io.File

/**
 * This is a helper class for remote providers that operate by sending full archives of volumes (e.g. S3 and S3web).
 * It handles the basics of creating or extracting the archives, and invokes a simpler upload / download interface.
 */
abstract class ArchiveRemote : RemoteServer {
    internal val executor = CommandExecutor()

    abstract fun pushArchive(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
        archive: File,
    )

    abstract fun pullArchive(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
        archive: File,
    )

    override fun syncDataVolume(
        operation: RemoteOperation,
        operationData: Any?,
        volumeName: String,
        volumeDescription: String,
        volumePath: String,
        scratchPath: String,
    ) {
        if (operation.type == RemoteOperationType.PUSH) {
            operation.updateProgress(RemoteProgress.START, "Creating archive for $volumeDescription", null)
            val archive = "$scratchPath/$volumeName.tar.gz"
            val args = arrayOf("tar", "czf", archive, ".")
            val process =
                ProcessBuilder()
                    .directory(File(volumePath))
                    .command(*args)
                    .start()
            executor.exec(process, args.joinToString())
            operation.updateProgress(RemoteProgress.END, null, null)

            operation.updateProgress(RemoteProgress.START, "Pushing archive for $volumeDescription", null)
            pushArchive(operation, operationData, volumeName, File(archive))
            File(archive).delete()
            operation.updateProgress(RemoteProgress.END, null, null)
        } else {
            operation.updateProgress(RemoteProgress.START, "Pulling archive for $volumeDescription", null)
            val archive = "$scratchPath/$volumeName.tar.gz"
            pullArchive(operation, operationData, volumeName, File(archive))
            operation.updateProgress(RemoteProgress.END, null, null)

            operation.updateProgress(
                RemoteProgress.START,
                "Extracting archive for $volumeDescription",
                null,
            )
            val args = arrayOf("tar", "xzf", archive)
            val process =
                ProcessBuilder()
                    .directory(File(volumePath))
                    .command(*args)
                    .start()
            executor.exec(process, args.joinToString())
            operation.updateProgress(RemoteProgress.END, null, null)
        }
    }
}

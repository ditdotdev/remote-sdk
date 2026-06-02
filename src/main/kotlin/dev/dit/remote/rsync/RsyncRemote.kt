/*
 * Copyright Dit.
 */

package dev.dit.remote.rsync

import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteProgress
import dev.dit.remote.RemoteServer
import dev.dit.shell.CommandExecutor

/**
 * This is a helper class for remote providers that operate by executing rsync (e.g. SSH). It handles the common
 * elements of syncing volumes.
 */
abstract class RsyncRemote : RemoteServer {
    internal val executor = CommandExecutor()

    abstract fun getRemotePath(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
    ): String

    abstract fun getRsync(
        operation: RemoteOperation,
        operationData: Any?,
        src: String,
        dst: String,
        executor: CommandExecutor,
    ): RsyncExecutor

    override fun syncDataVolume(
        operation: RemoteOperation,
        operationData: Any?,
        volumeName: String,
        volumeDescription: String,
        volumePath: String,
        scratchPath: String,
    ) {
        val remotePath = getRemotePath(operation, operationData, volumeName)
        val (src, dst) =
            if (operation.type == RemoteOperationType.PUSH) {
                volumePath to remotePath
            } else {
                remotePath to volumePath
            }

        operation.updateProgress(RemoteProgress.START, "Syncing $volumeDescription", 0)
        val rsync = getRsync(operation, operationData, src, dst, executor)
        rsync.run()
    }
}

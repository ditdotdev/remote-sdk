/*
 * Copyright Datadatdat.
 */

package com.datadatdat.remote

/**
 * Event types for progress updates. This is a subset of all types that can be posted in the server, because the
 * terminal states (error, abort, complete) are handled by the datadatdat server infrastructure.
 */
enum class RemoteProgress {
    MESSAGE, // Singular message, just display and move on.
    START, // Start of a longer running operation, where multiple progress message may appear followed by END
    PROGRESS, // Progress update within a (START, END) pair
    END, // End of a START operation
}

/**
 * Operation type.
 */
enum class RemoteOperationType {
    PUSH,
    PULL,
}

/**
 * All data associated with an operation:
 *
 *      updateProgress      Callback to post progress updates back to the server Comprises a type, optional message,
 *                          and optional percentage (0-100)
 *
 *                          Type        Always required.
 *                          Message     Required for MESSAGE and START. If not specified for PROGRESS, then percent
 *                                      must be included
 *                          Percent     Optional for PROGRESS
 *
 *      remote              Remote configuration
 *
 *      parameters          Remote parameters
 *
 *      operationId         Unique identifier for this operation
 *
 *      commitId            Commit being pushed or pulled
 *
 *      commit              Commit metadata, if this is a push operation.
 *
 *      type                Operation type (push or pull)
 */
data class RemoteOperation(
    val updateProgress: (RemoteProgress, String?, Int?) -> Unit,
    val remote: Map<String, Any>,
    val parameters: Map<String, Any>,
    val operationId: String,
    val commitId: String,
    val commit: Map<String, Any>?,
    val type: RemoteOperationType,
)

/**
 * The remote client interface defines functionality that runs in the context of the datadatdat server. It is responsible for
 * fetching remote commits and running push / pull operations.
 */
interface RemoteServer {
    /**
     * Returns the canonical name of this provider, such as "ssh" or "s3". This must be globally unique, and must
     * match the name used in the corresponding client.
     */
    fun getProvider(): String

    /**
     * Validates the configuration of a remote. If the CLi is using the RemoteUtil, it should never generate
     * malformed remotes, but this can serve as a backstop and also handle conversion cases (such as dealing with
     * integers that are transferred as numbers).
     */
    fun validateRemote(remote: Map<String, Any>): Map<String, Any>

    /**
     * Validates the configuration of remote parameters.
     */
    fun validateParameters(parameters: Map<String, Any>): Map<String, Any>

    /**
     * Fetches a set of commits from the remote server. Commits are simply a tuple of (commitId, properties), with
     * some properties having semantic significance (namely timestamp and tags). The remote provider should always
     * return commits in reverse timestamp order, optionally filtered by the given tags. There are utility methods
     * in RemoteServerUtil if remotes don't provide this functionality server-side. Tags are specified as a list of
     * pairs, where the first element is always the key and the second is optionally the value.
     *
     * There is not yet support for pagination, though that will be added in the future to avoid having to fetch
     * the entire commit history every time.
     */
    fun listCommits(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        tags: List<Pair<String, String?>>,
    ): List<Pair<String, Map<String, Any>>>

    /**
     * Fetches a single commit from the given remote. Returns null if no such commit exists.
     */
    fun getCommit(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        commitId: String,
    ): Map<String, Any>?

    /**
     * Starts a new data operation. This method is invoked once for each operation, and is passed all context around
     * the operation, including remote configuration, commit ID, and unique identifier for the operation. This
     * method may do nothing, but if needed it can do additional work and return operation-specific data that is
     * later passed to each syncVolume() invocation. This method is only called when syncing data, metadata-only
     * operations do not invoke this method (or any of the other
     */
    fun syncDataStart(operation: RemoteOperation): Any?

    /**
     * Syncs a particular volume, push or pull. The operation type can be determined by the 'type' member of the
     * operation data. The volume data will be accessible at the 'volumePath' location. The 'scratchPath' is created
     * once per operation, and can be used to store any temporary state, even across operations. The operationData
     * parameter is passed from the result of syncDataStart().
     */
    fun syncDataVolume(
        operation: RemoteOperation,
        operationData: Any?,
        volumeName: String,
        volumeDescription: String,
        volumePath: String,
        scratchPath: String,
    )

    /**
     * Ends the data portion of an operation. This is called after all volumes have been synced, regardless of success
     * or failure. The 'operationData' parameter is passed from teh result of syncDataStart(). The 'isSuccessful' flag
     * indicates whether the operation was successful.
     */
    fun syncDataEnd(
        operation: RemoteOperation,
        operationData: Any?,
        isSuccessful: Boolean,
    )

    /**
     * Push metadata for the commit to the remote. This can be done either when creating a new commit, or when
     * doing a metadata-only update (e.g. pushing new tags). The 'isUpdate' flag indicates whether we are updating
     * existing metadata or creating new metadata. This method is not called for pull operations.
     */
    fun pushMetadata(
        operation: RemoteOperation,
        commit: Map<String, Any>,
        isUpdate: Boolean,
    )
}

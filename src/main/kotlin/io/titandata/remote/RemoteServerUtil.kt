/*
 * Copyright The Titan Project Contributors.
 */

package io.titandata.remote

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class RemoteServerUtil {
    /**
     * Sorts a list of commits in reverse descending order, based on timestamp.
     */
    fun sortDescending(commits: List<Pair<String, Map<String, Any>>>): List<Pair<String, Map<String, Any>>> =
        commits.sortedByDescending {
            OffsetDateTime.parse(
                it.second.get("timestamp")?.toString()
                    ?: DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(0)),
                DateTimeFormatter.ISO_DATE_TIME,
            )
        }

    /**
     * Match a commit against a set of tags. Returns true if the commit matches the given tags, false otherwise.
     */
    fun matchTags(
        commit: Map<String, Any>,
        tags: List<Pair<String, String?>>,
    ): Boolean {
        if (tags.size == 0) {
            return true
        }

        val metadata = commit.get("tags")
        if (metadata == null || metadata !is Map<*, *>) {
            return false
        }
        @Suppress("UNCHECKED_CAST")
        metadata as Map<String, String>

        for (tag in tags) {
            val key = tag.first
            if (!metadata.containsKey(key)) {
                return false
            }

            if (tag.second != null && metadata.get(key) != tag.second) {
                return false
            }
        }

        return true
    }

    /**
     * Validate a set of properties (as with remotes and parameters) for required and optional fields.
     */
    fun validateFields(
        props: Map<String, Any>,
        required: List<String>,
        optional: List<String>,
    ) {
        for (prop in required) {
            if (!props.containsKey(prop)) {
                throw IllegalArgumentException("missing required property '$prop'")
            }
        }
        for (prop in props.keys) {
            if (!(prop in required) && !(prop in optional)) {
                throw IllegalArgumentException("invalid property '$prop'")
            }
        }
    }
}

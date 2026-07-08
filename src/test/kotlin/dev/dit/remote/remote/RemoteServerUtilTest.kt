// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec

class RemoteServerUtilTest : StringSpec() {
    private val util = RemoteServerUtil()

    private fun makeCommit(vararg tags: Pair<String, String>): Map<String, Any> {
        if (tags.size == 0) {
            return emptyMap()
        } else {
            return mapOf("tags" to mapOf(*tags))
        }
    }

    init {
        "empty tags allows any commit" {
            util.matchTags(makeCommit(), emptyList()) shouldBe true
            util.matchTags(makeCommit("a" to "b"), emptyList()) shouldBe true
            util.matchTags(makeCommit("c" to "d"), emptyList()) shouldBe true
        }

        "exact match works correctly" {
            val tags = listOf("a" to "b")
            util.matchTags(makeCommit(), tags) shouldBe false
            util.matchTags(makeCommit("a" to "b"), tags) shouldBe true
            util.matchTags(makeCommit("c" to "d"), tags) shouldBe false
        }

        "existence match works correctly" {
            val tags = listOf("a" to null)
            util.matchTags(makeCommit(), tags) shouldBe false
            util.matchTags(makeCommit("a" to "b"), tags) shouldBe true
            util.matchTags(makeCommit("c" to "d"), tags) shouldBe false
        }

        "multiple checks works correctly" {
            val tags = listOf("a" to null, "c" to "d")
            util.matchTags(makeCommit("a" to "b"), tags) shouldBe false
            util.matchTags(makeCommit("c" to "d"), tags) shouldBe false
            util.matchTags(makeCommit("a" to "b", "c" to "d"), tags) shouldBe true
        }

        "sort descending works" {
            val commits =
                listOf(
                    "four" to mapOf("timestamp" to "2019-09-21T13:45:30Z"),
                    "one" to mapOf("timestamp" to "2019-09-20T13:45:36Z"),
                    "three" to mapOf("timestamp" to "2019-09-20T13:45:38Z"),
                    "two" to mapOf("timestamp" to "2019-09-20T13:45:37Z"),
                )
            val sorted = util.sortDescending(commits)
            sorted.size shouldBe 4
            sorted[0].first shouldBe "four"
            sorted[1].first shouldBe "three"
            sorted[2].first shouldBe "two"
            sorted[3].first shouldBe "one"
        }

        "sort descending places commits without timestamps last" {
            val commits =
                listOf(
                    "four" to mapOf(),
                    "one" to mapOf("timestamp" to "2019-09-20T13:45:36Z"),
                    "three" to mapOf("timestamp" to "2019-09-20T13:45:38Z"),
                    "two" to mapOf("timestamp" to "2019-09-20T13:45:37Z"),
                )
            val sorted = util.sortDescending(commits)
            sorted.size shouldBe 4
            sorted[0].first shouldBe "three"
            sorted[1].first shouldBe "two"
            sorted[2].first shouldBe "one"
            sorted[3].first shouldBe "four"
        }

        "validate fields succeeds" {
            util.validateFields(mapOf("a" to "A", "b" to "B"), listOf("a"), listOf("b"))
        }

        "validate fields fails with missing required field" {
            shouldThrow<IllegalArgumentException> {
                util.validateFields(emptyMap(), listOf("a"), emptyList())
            }
        }

        "validate fields fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                util.validateFields(mapOf("c" to "C"), listOf("a"), listOf("b"))
            }
        }
    }
}

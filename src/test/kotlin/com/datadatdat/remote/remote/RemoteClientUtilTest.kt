/*
 * Copyright Datadatdat.
 */

package com.datadatdat.remote

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import java.lang.IllegalArgumentException
import java.net.URI

class RemoteClientUtilTest : StringSpec() {
    private val util = RemoteClientUtil()

    init {
        "get connection info without scheme fails" {
            shouldThrow<IllegalArgumentException> {
                util.getConnectionInfo(URI("foo"))
            }
        }

        "get full connection info succeeds" {
            val result = util.getConnectionInfo(URI("scheme://user:password@host:100/path"))
            result.username shouldBe "user"
            result.password shouldBe "password"
            result.host shouldBe "host"
            result.port shouldBe 100
            result.path shouldBe "/path"
        }

        "get connection info returns null password" {
            val result = util.getConnectionInfo(URI("scheme://user@host:100/path"))
            result.username shouldBe "user"
            result.password shouldBe null
            result.host shouldBe "host"
            result.port shouldBe 100
            result.path shouldBe "/path"
        }

        "get connection info returns null user" {
            val result = util.getConnectionInfo(URI("scheme://host:100/path"))
            result.username shouldBe null
            result.password shouldBe null
            result.host shouldBe "host"
            result.path shouldBe "/path"
        }

        "get connection info returns null port" {
            val result = util.getConnectionInfo(URI("scheme://user:password@host/path"))
            result.username shouldBe "user"
            result.password shouldBe "password"
            result.host shouldBe "host"
            result.port shouldBe null
            result.path shouldBe "/path"
        }

        "get connection info returns null host" {
            val result = util.getConnectionInfo(URI("scheme:///path"))
            result.username shouldBe null
            result.password shouldBe null
            result.host shouldBe null
            result.port shouldBe null
            result.path shouldBe "/path"
        }

        "get full connection return null path" {
            val result = util.getConnectionInfo(URI("scheme://user:password@host:100"))
            result.username shouldBe "user"
            result.password shouldBe "password"
            result.host shouldBe "host"
            result.port shouldBe 100
            result.path shouldBe null
        }
    }
}

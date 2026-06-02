/*
 * Copyright Dit.
 */

package dev.dit.remote.rsync

import dev.dit.remote.RemoteProgress
import dev.dit.shell.CommandException
import dev.dit.shell.CommandExecutor
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempFile

/**
 * This is a wrapper around the rsync command line tool, feeding progress back into the executor.
 * It is designed to work with any kind of remote SSH server, including engine remotes and SSH
 * remotes. The username and and host should be encoded into either the src and/or dst
 */
class RsyncExecutor(
    val updateProgress: (RemoteProgress, String?, Int?) -> Unit,
    val port: Int?,
    val password: String?,
    val key: String?,
    val src: String,
    val dst: String,
    val cmd: CommandExecutor = CommandExecutor(),
    val skipHostCheck: Boolean = false,
    val knownHostsFile: String? = null,
) : Runnable {
    companion object {
        val log = LoggerFactory.getLogger(RsyncExecutor::class.java)

        /**
         * Default known_hosts path used when [buildSshCommand] is invoked without
         * an explicit `knownHostsFile`. Mirrors OpenSSH's default of
         * `~/.ssh/known_hosts`. Resolved at call time, not at class load, so test
         * runners that override `user.home` continue to work.
         */
        internal fun defaultKnownHostsFile(): String = System.getProperty("user.home") + "/.ssh/known_hosts"
    }

    /**
     * Regular expression to match rsync progress. The rsync source code looks like the following:
     *
     *         ("\r%15s %3d%% %7.2f%s %s%s", human_num(ofs), pct, rate, units, rembuf, eol)
     *
     * Because we run with --no-human-readable, human_num() just prints the digits. While "units"
     * is one of "GB/s", "MB/s", or "kB/s". These numbers are left-padded with spaces, so we allow
     * for any number of spaces in between.
     */
    private val progressRegex = Regex("^\\s*(\\d+)\\s+(\\d+)%\\s+([\\d\\.]+)([GMk])B/s")

    /**
     * Regular expression for the completion line of the rsync process. The source code prints
     * this as:
     *
     *       "sent %s bytes  received %s bytes  %s bytes/sec\n",
     *              human_num(total_written), human_num(total_read), bytes_per_sec_human_dnum())
     *
     * Since we print with --no-human-readable, this is really just two longs (sent/received), and
     * a floating point number for the rate.
     */
    private val completionRegex = Regex("^sent\\s+(\\d+).*\\s+(\\d+) bytes\\s+([\\d\\.]+) bytes/sec")

    /**
     * Build the ssh (or sshpass) command used by rsync's `-e` argument.
     *
     * Host-key verification policy (see remote-sdk#46, ssh-remote#60 item #3):
     *
     *  - Default (`skipHostCheck=false`): emit `StrictHostKeyChecking=yes` plus
     *    a `UserKnownHostsFile` pointing at [knownHostsFile] (or
     *    `~/.ssh/known_hosts` if the caller did not override). Unknown hosts
     *    cause ssh to abort with `Host key verification failed.`, which the
     *    caller can translate into actionable guidance.
     *  - Opt-out (`skipHostCheck=true`): emit the legacy
     *    `StrictHostKeyChecking=no` + `UserKnownHostsFile=/dev/null` pair.
     *    Intended for trusted networks / CI where TOFU is acceptable.
     *
     * Both new parameters are appended at the end of the parameter list with
     * default values so existing positional callers compile unchanged.
     */
    fun buildSshCommand(
        file: File,
        vararg command: String,
        skipHostCheck: Boolean = false,
        knownHostsFile: String? = null,
    ): List<String> {
        val args = mutableListOf<String>()

        if (password != null && key != null) {
            throw IllegalArgumentException("only one of password or key can be specified")
        } else if (password != null) {
            file.writeText(password)
            args.addAll(arrayOf("sshpass", "-f", file.path, "ssh"))
        } else if (key != null) {
            file.writeText(key)
            args.addAll(arrayOf("ssh", "-i", file.path))
        } else {
            throw IllegalArgumentException("one of password or key must be specified")
        }

        // Set file permissions only on POSIX-compliant systems (not Windows)
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                mutableSetOf(
                    PosixFilePermission.OWNER_READ,
                ),
            )
        } catch (e: UnsupportedOperationException) {
            // POSIX file permissions not supported on this platform (e.g., Windows)
            // File permissions will be handled by the OS defaults
        }

        if (port != null) {
            args.addAll(arrayOf("-p", port.toString()))
        }

        if (skipHostCheck) {
            args.addAll(arrayOf("-o", "StrictHostKeyChecking=no"))
            args.addAll(arrayOf("-o", "UserKnownHostsFile=/dev/null"))
        } else {
            val resolvedKnownHosts = knownHostsFile ?: defaultKnownHostsFile()
            args.addAll(arrayOf("-o", "StrictHostKeyChecking=yes"))
            args.addAll(arrayOf("-o", "UserKnownHostsFile=$resolvedKnownHosts"))
        }
        args.addAll(command)

        return args
    }

    override fun run() {
        // createTempFile() uses the JVM's default temp dir and only the current user has read
        // permission on the new file (POSIX 0600). We delete it eagerly in the finally block
        // below; deleteOnExit() is intentionally avoided because it only runs on normal JVM
        // shutdown and is not guaranteed on abnormal exit.
        val file = createTempFile().toFile()
        try {
            /*
             * Assuming that the remote and parameters are both from the SSH provider is not a
             * valid assumption. We will have to revisit this when we have add a second provider
             * that re-uses the rsync executor.
             *
             * Forward the constructor-supplied [skipHostCheck] and [knownHostsFile] so the rsync
             * data-transfer SSH invocation honors the same host-key verification policy the
             * caller configured. Without this, the data path silently used the
             * [buildSshCommand] defaults (secure post-#46, but no opt-out reachable from
             * consumers) regardless of what the provider passed at construction time.
             */
            val sshCommand =
                buildSshCommand(
                    file,
                    skipHostCheck = skipHostCheck,
                    knownHostsFile = knownHostsFile,
                )
            val args =
                arrayOf(
                    "rsync",
                    "-e",
                    sshCommand.joinToString(" "),
                    "--info=progress2,stats2",
                    "--inplace",
                    "--delete",
                    "--partial",
                    "-raz",
                    "--rsync-path=sudo rsync",
                    "--no-human-readable",
                    src,
                    dst,
                )
            log.info("running command: ${args.joinToString()}")
            val process = cmd.start(*args)
            try {
                // Closing the input stream is the consumer's responsibility; if we let
                // process.destroy() do it we leak the underlying file descriptor on every
                // invocation. processOutput() wraps the stream in .use{} so the underlying
                // file descriptor is closed before we destroy the process, in both success
                // and failure paths.
                val endSeen = processOutput(process.inputStream)

                // If we've consumed all stdout, the process should be done but wait to be sure
                process.waitFor()

                // Check exit code and throw error if necessary
                cmd.checkResult(process)

                // If we get here, something has gone wrong - the command succeeded but we didn't
                // see the expected termination line.
                if (!endSeen) {
                    log.error("rsync succeeded, but no summary statistics found")
                    val msg = "rsync command succeeded, but failed to find summary statistics"
                    throw CommandException(msg, 0, msg)
                }
                log.info("rsync succeeded")
            } catch (e: CommandException) {
                log.error("rsync command failed: ${e.output}")
                throw e
            } finally {
                process.destroy()
                file.delete()
            }
        } finally {
            // Belt-and-suspenders: ensure the temp file is gone even if buildSshCommand
            // threw before the inner try/finally could clean it up.
            file.delete()
        }
    }

    fun processOutput(output: InputStream): Boolean =
        output.bufferedReader().use { reader ->
            var endSeen = false
            var line = reader.readLine()
            while (line != null) {
                endSeen = endSeen || processLine(line.trim())
                line = reader.readLine()
            }
            endSeen
        }

    /**
     * We run rsync with the argument "--info=progress2,stats2". This will create three bodies of
     * output:
     *
     *           28045990  99%   15.57MB/s    0:00:01 (xfr#203, to-chk=0/301)
     *      ...
     *
     *      Number of files: 301 (reg: 203, dir: 29, link: 69)
     *      Number of created files: 301 (reg: 203, dir: 29, link: 69)
     *      ...
     *
     *      sent 11158327 bytes  received 4227 bytes  4465021.60 bytes/sec
     *      total size is 28047108  speedup is 2.51
     *
     * To process this, our strategy is:
     *
     *      * If the line is blank, ignore it
     *      * If this is a progress line, generate and add a ProgressEntry record
     *      * Log the output
     *      * If this is one of the summary lines, generate an END record with the summary
     *
     * This returns true if the line is the completion line, false otherwise.
     */
    fun processLine(line: String): Boolean {
        if (line == "") {
            return false
        }

        val progressMatch = progressRegex.find(line)
        if (progressMatch != null) {
            val sent = progressMatch.groupValues[1].toLong()
            val percent = progressMatch.groupValues[2].toInt()
            val rawRate = progressMatch.groupValues[3].toDouble()
            val rate =
                when (progressMatch.groupValues[4]) {
                    "G" -> rawRate * 1024 * 1024 * 1024
                    "M" -> rawRate * 1024 * 1024
                    "k" -> rawRate * 1024
                    else -> throw IllegalStateException("unknown rsync units '${progressMatch.groupValues[4]}'")
                }

            updateProgress(
                RemoteProgress.PROGRESS,
                "${numberToString(sent.toDouble())}B (${numberToString(rate)}B/s)",
                percent,
            )
            return false
        }

        log.debug(line)

        val completionMatch = completionRegex.find(line)
        if (completionMatch != null) {
            val sent = completionMatch.groupValues[1].toLong()
            val received = completionMatch.groupValues[2].toLong()
            val rate = completionMatch.groupValues[3].toDouble()

            val sentStr = numberToString(sent.toDouble()) + "B"
            val receivedStr = numberToString(received.toDouble()) + "B"
            val rateStr = numberToString(rate) + "B/sec"

            updateProgress(RemoteProgress.END, "$sentStr sent  $receivedStr received  ($rateStr)", null)
            return true
        }

        return false
    }

    /**
     * This is a pretty printing function for numbers with bases, modeled after ZFS behavior. It
     * essentially takes converts it to a unit scale and prints it with four digits of precision.
     * Technically, this should be "Ki" etc since we're using base 1024, but despite being
     * correct it's more confusing to the average user.
     */
    fun numberToString(rawValue: Double): String {
        val suffixes = arrayOf("", "K", "M", "G", "T", "P", "E")
        var suffix = ""
        var value = rawValue
        for (s in suffixes) {
            if (value < 1024) {
                suffix = s
                break
            }
            value /= 1024
        }

        var valueStr: String
        if (value > 1024 || Math.round(value).toDouble() == value) {
            valueStr = Math.round(value).toString()
        } else if (value < 10) {
            valueStr = "%.2f".format(value)
        } else {
            valueStr = BigDecimal(value, MathContext(4)).toString()
        }

        return valueStr + suffix
    }
}

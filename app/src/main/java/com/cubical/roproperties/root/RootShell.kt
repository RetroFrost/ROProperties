package com.cubical.roproperties.root

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
) {
    val successful: Boolean get() = exitCode == 0 && !timedOut
}

class RootShell {
    suspend fun plain(command: String, timeoutSeconds: Long = 20): CommandResult =
        execute(listOf("/system/bin/sh", "-c", command), timeoutSeconds)

    suspend fun root(command: String, timeoutSeconds: Long = 20): CommandResult =
        execute(listOf("su", "-c", command), timeoutSeconds)

    private suspend fun execute(
        command: List<String>,
        timeoutSeconds: Long,
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                val output = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { it.readText() }
                }
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    output.await()
                    CommandResult(-1, "Command timed out", timedOut = true)
                } else {
                    CommandResult(process.exitValue(), output.await().trimEnd())
                }
            }
        } catch (error: Exception) {
            CommandResult(-1, error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        /** POSIX-safe single argument quoting. Newlines are rejected by repository validation. */
        fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

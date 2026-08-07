package dev.retrofrost.roproperties.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShellResult(
    val exitCode: Int,
    val output: String,
) {
    val ok: Boolean get() = exitCode == 0
}

class ShellExecutor {
    suspend fun shell(command: String): ShellResult = execute(listOf("sh", "-c", command))

    suspend fun root(command: String): ShellResult = execute(listOf("su", "-c", command))

    private suspend fun execute(command: List<String>): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            ShellResult(exitCode, output)
        } catch (error: Throwable) {
            ShellResult(-1, error.message ?: error::class.java.simpleName)
        }
    }
}

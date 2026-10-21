package io.heapy.ktc.plugins.dokka

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun executeProcess(
    command: List<String>,
    directory: Path,
    timeoutSeconds: Int,
    log: Path? = null,
): Int = log?.let { Files.newOutputStream(it) }.use { logOutput ->
    // inheritIO bypasses the System streams intercepted by the Toolchain task logger.
    val stdout = System.out
    val stderr = System.err
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
    val process = ProcessBuilder(command).directory(directory.toFile())
        .redirectInput(ProcessBuilder.Redirect.INHERIT).start()
    val readers = Executors.newFixedThreadPool(2) { action ->
        Thread(action, "dokka-process-output").apply { isDaemon = true }
    }
    try {
        val output = listOf(
            readers.submit { forwardOutput(process.inputStream, stdout, logOutput) },
            readers.submit { forwardOutput(process.errorStream, stderr, logOutput) },
        )
        check(process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            "Process timed out after $timeoutSeconds seconds: ${command.first()}"
        }
        // Drain both pipes before returning or reporting a non-zero exit, including unterminated lines.
        output.forEach { it.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
        process.exitValue()
    } catch (exception: TimeoutException) {
        throw IllegalStateException("Process output timed out after $timeoutSeconds seconds: ${command.first()}", exception)
    } catch (exception: ExecutionException) {
        throw IllegalStateException("Cannot forward process output: ${command.first()}", exception.cause)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw exception
    } finally {
        try {
            if (process.isAlive) {
                try {
                    val children = process.descendants().use { it.toList() }
                    children.asReversed().forEach { it.destroyForcibly() }
                } finally {
                    process.destroyForcibly()
                }
            }
        } finally {
            readers.shutdownNow()
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}

private fun forwardOutput(source: InputStream, destination: PrintStream, log: OutputStream?) {
    source.use {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (log != null) synchronized(log) {
                log.write(buffer, 0, count)
                log.flush()
            }
            destination.write(buffer, 0, count)
            destination.flush()
        }
    }
}

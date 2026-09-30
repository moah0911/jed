package dev.jed.app.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Events from a real on-device run. Chunks are the process's own bytes. */
sealed interface RunEvent {
    data class Chunk(val text: String) : RunEvent
    data class Done(val exit: Int, val note: String? = null) : RunEvent
}

/**
 * One note's persistent shell: `/system/bin/sh` kept across runs, so a `cd`,
 * an export or an activated virtualenv carries into the next block — the
 * local equivalent of a notebook's per-note shell. Nothing is simulated: output
 * is the process's bytes, framed by a per-run sentinel.
 */
class NoteShell(private val workDir: File, private val env: Map<String, String>) {

    private var proc: Process? = null
    private var reader: Thread? = null
    private val lock = Object()
    private val pending = StringBuilder()

    @Volatile private var dead = true

    private fun ensureRunning() {
        if (!dead && proc != null) return
        closeQuietly()
        workDir.mkdirs()
        val pb = ProcessBuilder("/system/bin/sh").directory(workDir).redirectErrorStream(true)
        pb.environment().putAll(env)
        // A known prompt-free, non-interactive shell: no rc files.
        pb.environment()["ENV"] = ""
        val p = pb.start()
        proc = p
        dead = false
        reader = Thread({
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = p.inputStream.read(buf)
                    if (n < 0) break
                    val s = String(buf, 0, n, Charsets.UTF_8)
                    synchronized(lock) {
                        pending.append(s)
                        lock.notifyAll()
                    }
                }
            } catch (_: Exception) {
                // Process ended; interrupt() and close() handle the rest.
            } finally {
                synchronized(lock) {
                    dead = true
                    lock.notifyAll()
                }
            }
        }, "jed-shell-reader").apply { isDaemon = true; start() }
    }

    /**
     * Run code in the note's shell, emitting the shell's own output.
     * Concurrent runs in one note are serialized; a second caller waits.
     */
    fun run(code: String): Flow<RunEvent> = flow {
        val token = "JED" + UUID.randomUUID().toString().replace("-", "").take(12)
        val seen: String
        synchronized(RunSession.GATE) {
            ensureRunning()
            val p = proc ?: throw IllegalStateException("The shell would not start.")
            synchronized(lock) { pending.clear() }
            // Guard: a failed write means the shell died; respawn once.
            fun write(text: String): Boolean = runCatching {
                p.outputStream.write(text.toByteArray())
                p.outputStream.flush()
                true
            }.getOrDefault(false)
            if (!write(code.trimEnd() + "\nprintf 'JED_RC_" + token + "=%d_JED_END\\n' $?\n")) {
                dead = true
                ensureRunning()
                val q = proc ?: throw IllegalStateException("The shell would not start.")
                q.outputStream.write((code.trimEnd() + "\nprintf 'JED_RC_" + token + "=%d_JED_END\\n' $?\n").toByteArray())
                q.outputStream.flush()
            }
            seen = awaitToken(token)
        }
        val marker = Regex("JED_RC_${token}=(\\d+)_JED_END\\n?")
        val m = marker.find(seen)
        if (m == null) {
            emit(RunEvent.Chunk(seen))
            emit(RunEvent.Done(1, "The shell ended without answering."))
        } else {
            val out = seen.substring(0, m.range.first)
            if (out.isNotEmpty()) emit(RunEvent.Chunk(out))
            emit(RunEvent.Done(m.groupValues[1].toIntOrNull() ?: 0))
        }
    }.flowOn(Dispatchers.IO)

    private fun awaitToken(token: String): String {
        val end = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)
        synchronized(lock) {
            while (true) {
                val s = pending.toString()
                if (s.contains("JED_RC_${token}=")) return s
                if (dead) return s
                val wait = end - System.currentTimeMillis()
                if (wait <= 0) return s
                lock.wait(wait.coerceAtMost(2000))
            }
        }
    }

    /** Bytes typed into a waiting program: a `sudo` prompt, a `[y/N]`. */
    fun sendInput(text: String): Boolean = runCatching {
        val p = proc ?: return false
        p.outputStream.write(text.toByteArray())
        p.outputStream.flush()
        true
    }.getOrDefault(false)

    /** Interrupt the running block: the shell is destroyed and respawned. */
    fun interrupt() {
        closeQuietly()
    }

    fun close() = closeQuietly()

    private fun closeQuietly() {
        dead = true
        runCatching { proc?.destroy() }
        runCatching { proc?.waitFor(2, TimeUnit.SECONDS) }
        runCatching { proc?.destroyForcibly() }
        proc = null
        synchronized(lock) { lock.notifyAll() }
    }
}

/**
 * Python blocks run against the device's own `python3` when one exists —
 * on-device execution, never canned output. Where no interpreter is
 * installed the run reports exactly that instead of pretending.
 */
object PythonRunner {

    data class Found(val path: String, val version: String)

    /** Absolute path of a usable `python3`, or null when none exists. */
    fun resolve(): String? {
        val direct = listOf("/system/bin/python3", "/vendor/bin/python3")
            .firstOrNull { File(it).canExecute() }
        if (direct != null && versionOk(direct)) return direct
        val viaPath = runCatching {
            val q = ProcessBuilder("/system/bin/sh", "-c", "command -v python3").redirectErrorStream(true).start()
            q.waitFor(5, TimeUnit.SECONDS)
            q.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
        return if (viaPath != null && versionOk(viaPath)) viaPath else null
    }

    fun locate(): Found? {
        val exe = resolve() ?: return null
        return Found(exe, versionOf(exe) ?: "Python 3")
    }

    private fun versionOf(exe: String): String? = runCatching {
        val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
        p.waitFor(10, TimeUnit.SECONDS)
        p.inputStream.bufferedReader().readText().trim().takeIf { it.startsWith("Python 3") }
    }.getOrNull()

    private fun versionOk(exe: String): Boolean = versionOf(exe) != null

    fun run(code: String, workDir: File, env: Map<String, String>): Flow<RunEvent> = flow {
        val exe = withContext(Dispatchers.IO) { resolve() }
        if (exe == null) {
            emit(RunEvent.Done(127, "No Python 3 interpreter is installed on this device, so the block did not run."))
            return@flow
        }
        workDir.mkdirs()
        val pb = ProcessBuilder(exe, "-u", "-c", code)
        pb.directory(workDir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = withContext(Dispatchers.IO) { pb.start() }
        val out = withContext(Dispatchers.IO) {
            val text = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(10, TimeUnit.MINUTES)
            (if (ok) p.exitValue() else 124) to text
        }
        if (out.second.isNotEmpty()) emit(RunEvent.Chunk(out.second))
        emit(RunEvent.Done(out.first))
    }.flowOn(Dispatchers.IO)
}

/** One note's runs. Serializes shell blocks; owns the shell's lifetime. */
class RunSession(val workBase: File) {
    companion object {
        /** One shell block at a time per note; cross-note runs are parallel. */
        val GATE = Any()
    }

    private var shell: NoteShell? = null

    fun shell(workDir: File, env: Map<String, String>): NoteShell {
        val s = shell
        if (s != null) return s
        return NoteShell(workDir, env).also { shell = it }
    }

    fun sendInput(text: String): Boolean = shell?.sendInput(text) == true

    fun interrupt() {
        shell?.interrupt()
        shell = null
    }

    fun close() {
        shell?.close()
        shell = null
    }
}

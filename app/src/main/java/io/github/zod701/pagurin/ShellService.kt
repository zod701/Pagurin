package io.github.zod701.pagurin

import kotlin.system.exitProcess

// Runs in a process of its own that Shizuku starts as the shell user (adb's rights: other apps' Android/data folders, am, pm), for ShizukuShell only:
// the binder reaches Pagurin alone, and the scripts are Pagurin's own
class ShellService : IShell.Stub() {
    override fun destroy() { exitProcess(0) }

    override fun run(script: String): String {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        return out
    }
}

package io.github.zod701.pagurin

import android.os.IBinder
import android.os.Parcel

// The root service of AYN's firmware (pservice, binder PServerBinder): the backend of its settings' "Run Script as Root". It runs a shell command as root and answers with
// the first line of its output only, and it drops a long command: a longer job is a script file, run with a short command. Reachable from an app only while SELinux is
// Permissive, AYN's default; on other devices, or with SELinux enforcing, available() is false
object AynRoot {
    @Volatile private var ok: Boolean? = null

    fun available(): Boolean = ok ?: (runCatching { exec("id")?.contains("uid=0") == true }.getOrDefault(false)).also { ok = it }

    @Synchronized fun exec(cmd: String): String? {
        val binder = runCatching { Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "PServerBinder") as? IBinder }.getOrNull() ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "0"))   // the command, and 0: wait for it
            if (binder.transact(0, data, reply, 0)) reply.createByteArray()?.let { String(it) } ?: "" else null
        } catch (e: Exception) { null } finally { data.recycle(); reply.recycle() }
    }
}

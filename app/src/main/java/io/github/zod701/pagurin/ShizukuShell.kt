package io.github.zod701.pagurin

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// Shizuku (an app that, started once over wireless debugging or root, lends apps the shell user's rights): what AynRoot does on AYN devices, on any device, but
// as the shell rather than root, so another app's internal storage stays closed (a port's save comes through its own save manager instead: Store.takeOver)
object ShizukuShell {
    private var shell: IShell? = null

    fun running(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun granted(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    // Shizuku's own dialog; true when Pagurin may use it
    suspend fun ask(): Boolean {
        if (granted()) return true
        if (!running() || runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) return false   // "deny and don't ask again"
        return suspendCancellableCoroutine { c ->
            val l = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST) return
                    Shizuku.removeRequestPermissionResultListener(this); if (c.isActive) c.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(l); c.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(l) }
            Shizuku.requestPermission(REQUEST)
        }
    }

    // A script's output, or null when the shell could not be reached. Not on the main thread: it waits for the service, which connects there
    @Synchronized fun exec(context: Context, script: String): String? = runCatching { (shell?.takeIf { it.asBinder().pingBinder() } ?: bind(context))?.run(script) }.getOrNull()

    private fun bind(context: Context): IShell? {
        if (!granted()) return null
        val done = CountDownLatch(1)
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name)).daemon(false).processNameSuffix("shell")
            .version(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
        Shizuku.bindUserService(args, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { shell = binder?.takeIf { it.pingBinder() }?.let { IShell.Stub.asInterface(it) }; done.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { shell = null }
        })
        done.await(15, TimeUnit.SECONDS)
        return shell
    }

    private const val REQUEST = 7301
}

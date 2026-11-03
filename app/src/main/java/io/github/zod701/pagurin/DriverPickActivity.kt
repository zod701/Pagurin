package io.github.zod701.pagurin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.util.zip.ZipFile
import kotlin.concurrent.thread

// Adds a GPU driver package from a file the person chooses. The driver screen starts it.
class DriverPickActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (saved != null) return   // recreated with the picker open: its result still comes here
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(pick, 1) } catch (e: ActivityNotFoundException) { end(getString(R.string.picker_missing)) }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        val uri = data?.data.takeIf { result == RESULT_OK } ?: return finish()
        thread(name = "driver pick") {   // a package is megabytes, on the picker's provider
            val message = runCatching { getString(R.string.driver_added, add(uri)) }.getOrElse { getString(R.string.driver_add_failed, it.message) }
            runOnUiThread { end(message) }
        }
    }

    private fun end(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish() }

    private fun add(uri: Uri): String {
        val drivers = File(getExternalFilesDir(null), "drivers").apply { mkdirs() }
        val partial = File(drivers, ".adding")   // a dot: not a driver until it is whole and checked
        try {
            checkNotNull(contentResolver.openInputStream(uri)) { getString(R.string.picker_read_failed) }.use { from -> partial.outputStream().use { from.copyTo(it) } }
            check(hasLibrary(partial)) { getString(R.string.picker_not_driver) }
            val name = driverName(uri)
            check(partial.renameTo(File(drivers, "$name.zip"))) { getString(R.string.picker_move_failed) }
            return name
        } finally { partial.delete() }
    }

    private fun hasLibrary(f: File) = runCatching { ZipFile(f).use { z -> z.entries().asSequence().any { it.name.endsWith(".so") } } }.getOrDefault(false)

    private fun driverName(uri: Uri): String {
        val shown = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        return shown.orEmpty().replace(Regex("(?i)(\\.(zip|adpkg))+$"), "").replace(Regex("[^A-Za-z0-9._+() -]"), "_").trimStart('.', ' ').take(100).ifEmpty { "driver" }
    }
}

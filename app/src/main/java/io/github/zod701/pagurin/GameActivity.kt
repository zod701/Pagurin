package io.github.zod701.pagurin

import android.app.AlertDialog
import android.app.NativeActivity
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.ArrayAdapter
import java.util.concurrent.Executors

// The game: the NativeActivity the stub runs the host in, with what the host asks of its activity. The pause menu's audio row (front_android.c menu_audio) calls
// openAudioMenu, which a host from 2026-11-13 on looks up and shows the row for. The game's sound goes to the device picked there until the game ends
class GameActivity : NativeActivity() {
    // The host's own entry points for its audio route (front_android.c externalAudio, externalAudioSelected), which the stub registers here from the host it loaded.
    // 0 is the system's choice
    private external fun audioRoute(id: Int)
    private external fun audioSelected(): Int

    private val audioThread = Executors.newSingleThreadExecutor()   // the host reopens its output stream: not on the UI thread, as nx2android's ExternalActivity does it
    private var audioDialog: AlertDialog? = null

    // From the host's input thread
    @Suppress("unused")
    fun openAudioMenu() = runOnUiThread(::showAudioMenu)

    private fun showAudioMenu() {
        if (audioDialog?.isShowing == true) return
        val text = Language.wrap(this)   // the app's language; the activity itself keeps the host's configuration
        val manager = getSystemService(AudioManager::class.java)
        val ids = mutableListOf<Int>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice)
        val dialog = AlertDialog.Builder(this).setTitle(text.getString(R.string.audio_output))
            .setSingleChoiceItems(adapter, -1) { d, which -> val id = ids[which]; d.dismiss(); audioThread.execute { audioRoute(id) } }
            .setNegativeButton(text.getString(R.string.cancel), null).create()
        // The outputs there are now, again whenever one comes or goes while the list is open
        val refresh = {
            ids.clear(); adapter.clear()
            ids += 0; adapter.add(text.getString(R.string.audio_default))
            for (d in manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) outputName(d, text)?.let { ids += d.id; adapter.add(it) }
            dialog.listView?.let { list -> list.clearChoices(); ids.indexOf(audioSelected()).takeIf { it >= 0 }?.let { list.setItemChecked(it, true) } }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { refresh() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { refresh() }
        }
        dialog.setOnShowListener { refresh() }
        dialog.setOnDismissListener { manager.unregisterAudioDeviceCallback(callback); audioDialog = null }
        manager.registerAudioDeviceCallback(callback, null)
        audioDialog = dialog
        dialog.show()
    }

    // An output a person can pick, by what it is; null for the system's own (telephony, a hearing aid stream, the echo reference)
    private fun outputName(d: AudioDeviceInfo, text: android.content.Context): String? {
        val product = d.productName?.toString()?.trim().orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> text.getString(R.string.audio_speaker)
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> text.getString(R.string.audio_wired)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> product.ifEmpty { text.getString(R.string.audio_bluetooth) }
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> text.getString(R.string.audio_hdmi, product)
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> text.getString(R.string.audio_usb, product)
            else -> null
        }
    }
}

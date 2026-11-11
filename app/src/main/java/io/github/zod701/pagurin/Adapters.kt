package io.github.zod701.pagurin

import androidx.annotation.StringRes
import org.json.JSONObject

// A value and how it reads: a string resource, or as the text itself where no words need translating
data class Choice(val value: String, @StringRes val label: Int = 0, val text: String = value)

// One setting a kind of port reads at its start. inEnv: a row of env.txt, else of settings.txt (nx2android's two files).
// marker: text the host library holds when it reads this setting, as a C string; a host without it gets no row for the setting
data class Setting(val key: String, @StringRes val label: Int, @StringRes val group: Int, val inEnv: Boolean, val choices: List<Choice>, val marker: String)

// How a kind of port's saves are reached: HOST_REQUEST, through the host's own save manager (a request file, then a start); NONE, not from Pagurin
enum class SaveMode { HOST_REQUEST, NONE }

// What Pagurin knows about one kind of port: the file that marks its game folder, what can be set and what the host does when it is not, how its saves and GPU drivers
// are reached. Each kind is studied once, for all its games; a game of a kind Pagurin does not know is not listed
interface Adapter {
    val kind: String
    val manifest: String
    val settings: List<Setting>
    fun hostDefault(key: String): String
    val saves: SaveMode
    val driverMarker: String?   // a host holding it takes the GPU driver Pagurin names; null: the kind takes none

    // A word of the kind's own that a game carries on its card and overview, from its manifest; null: the kind has none
    @StringRes fun badge(manifest: JSONObject): Int? = null

    // The settings as they apply to one game, from its manifest: a choice the game lacks dropped, a setting with fewer than two choices left out
    fun gameSettings(manifest: JSONObject): List<Setting> = settings

    val languageKey: String? get() = null   // the setting the app's game language sets (Store.setGameLanguage), its values GAME_LANGUAGES' codes; null: none

    // The settings, and the driver ("driver"), this host reads: the markers found in its library
    fun hostKnows(host: ByteArray): Set<String> =
        (settings.filter { contains(host, it.marker) }.map { it.key } + listOfNotNull("driver".takeIf { driverMarker != null && contains(host, driverMarker!!) })).toSet()

    companion object {
        fun contains(hay: ByteArray, text: String): Boolean {
            val needle = (text + '\u0000').toByteArray(Charsets.ISO_8859_1)   // whole C strings only: DM_VK_PASS_SPLIT is not found in DM_VK_PASS_SPLIT_DEPTH
            outer@ for (i in 0..hay.size - needle.size) { for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer; return true }
            return false
        }
    }
}

private val ON_OFF = listOf(Choice("1", R.string.on), Choice("0", R.string.off))

// The languages a Switch game can have, in its control data's order (nx2android's nacp.py LANGUAGES), each named in its own language: the app's game language
// and nx2android's DM_LANG choose among them
val GAME_LANGUAGES = listOf("en-US" to "English (US)", "en-GB" to "English (UK)", "ja" to "日本語", "fr" to "Français", "de" to "Deutsch",
    "es-419" to "Español (Latinoamérica)", "es" to "Español", "it" to "Italiano", "nl" to "Nederlands", "fr-CA" to "Français (Canada)", "pt" to "Português",
    "ru" to "Русский", "ko" to "한국어", "zh-Hant" to "繁體中文", "zh-Hans" to "简体中文", "pt-BR" to "Português (Brasil)").map { (code, name) -> Choice(code, text = name) }

// Ports made with nx2android: a data pack with nx2a-data.json, its host run by Pagurin's stub. The settings are what its host reads at its start
// (front_android.c settings_load reads settings.txt, gfx_vk_exec.c and perf_android.c read env.txt's switches), nothing the engine lacks
object Nx2Android : Adapter {
    override val kind = "nx2android"
    override val manifest = "nx2a-data.json"
    override val saves = SaveMode.HOST_REQUEST
    override val driverMarker = "DM_VK_DRIVER"
    override val settings = listOf(
        Setting("docked", R.string.setting_docked, R.string.group_graphics, false, listOf(Choice("0", R.string.mode_handheld), Choice("1", R.string.mode_docked)), "docked=%d"),
        Setting("overlay", R.string.setting_overlay, R.string.group_input, false, listOf(Choice("1", R.string.overlay_show), Choice("0", R.string.overlay_hide)), "overlay=%d"),
        Setting("opacity", R.string.setting_opacity, R.string.group_input, false, (20..80 step 5).map { Choice("$it", text = "$it%") }, "opacity=%d"),
        Setting("rumble", R.string.setting_rumble, R.string.group_input, false, ON_OFF, "rumble=%d"),
        // A controller's face buttons by their position (the Switch's layout) or by their label (front_android.c pad_labels, the pause menu's Face buttons row)
        Setting("labels", R.string.setting_face_buttons, R.string.group_input, false, listOf(Choice("0", R.string.pad_position), Choice("1", R.string.pad_label)), "labels=%d"),
        Setting("DM_VK_PASS_SPLIT", R.string.setting_pass_split, R.string.group_performance, true, listOf(Choice("0", R.string.off), Choice("64"), Choice("128"), Choice("256")), "DM_VK_PASS_SPLIT"),
        Setting("DM_VK_PASS_SPLIT_DEPTH", R.string.setting_pass_split_depth, R.string.group_performance, true, ON_OFF, "DM_VK_PASS_SPLIT_DEPTH"),
        Setting("DM_VK_SETSPLIT", R.string.setting_set_split, R.string.group_performance, true, ON_OFF, "DM_VK_SETSPLIT"),
        Setting("DM_PIN_REPLAY", R.string.setting_pin_replay, R.string.group_performance, true, listOf(Choice("0", R.string.off), Choice("big", R.string.pin_big), Choice("prime", R.string.pin_prime)), "DM_PIN_REPLAY"),
        Setting("fps", R.string.setting_fps, R.string.group_display, false, ON_OFF, "fps=%d"),
        // The language the game is told the system has (hle_oe.c GetDesiredLanguage and GetRegionCode answer from DM_LANG)
        Setting("DM_LANG", R.string.setting_language, R.string.group_system, true, GAME_LANGUAGES, "DM_LANG"),
    )
    override val languageKey get() = "DM_LANG"

    // What the host does without the row (front_android.c g_set_*, hle_oe.c: docked unless the profile says otherwise, gfx_vk_exec.c, perf_android.c)
    private val defaults = mapOf("docked" to "1", "overlay" to "1", "opacity" to "60", "rumble" to "1", "labels" to "0", "fps" to "0",
        "DM_VK_PASS_SPLIT" to "0", "DM_VK_PASS_SPLIT_DEPTH" to "0", "DM_VK_SETSPLIT" to "0", "DM_PIN_REPLAY" to "0", "DM_LANG" to "en-US")
    override fun hostDefault(key: String) = defaults.getValue(key)

    // The languages the game has, which the import tool reads from its control data (pagurin.languages): a language it lacks is not offered, as in the
    // engine's own launchers (settings.py). Unknown (a port taken from an installed app, or an older import): no language row
    override fun gameSettings(manifest: JSONObject): List<Setting> {
        val has = manifest.optJSONObject("pagurin")?.optJSONArray("languages")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        return settings.mapNotNull { s -> if (s.key != "DM_LANG") s else s.copy(choices = s.choices.filter { it.value in has }).takeIf { it.choices.size > 1 } }
    }

    // The game profile's status (android.tsv, which the port copies into nx2a-data.json); none written: experimental, as porter treats it
    override fun badge(manifest: JSONObject) = when (manifest.optString("status")) {
        "playable" -> R.string.status_playable; "wip" -> R.string.status_wip; "boots" -> R.string.status_boots; else -> R.string.status_experimental }
}

// The app's game language for one game's language setting: that language when the game has it, else English, else the game's first
fun pickLanguage(s: Setting, code: String) = (s.choices.firstOrNull { it.value == code } ?: s.choices.firstOrNull { it.value == "en-US" } ?: s.choices.first()).value

// Every kind Pagurin runs. Another kind of port is one more object here, after it has been studied: where its games are marked, what it reads, where its saves are
val ADAPTERS: List<Adapter> = listOf(Nx2Android)

package io.github.zod701.pagurin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptersTest {
    // A host library in miniature: C strings among other bytes
    private fun host(vararg strings: String) = (listOf("\u0001\u0002junk") + strings.toList() + "tail").joinToString("\u0000").toByteArray(Charsets.ISO_8859_1)

    @Test fun wholeStringsOnly() {
        val h = host("DM_VK_PASS_SPLIT_DEPTH")
        assertFalse(Adapter.contains(h, "DM_VK_PASS_SPLIT"))
        assertTrue(Adapter.contains(h, "DM_VK_PASS_SPLIT_DEPTH"))
    }

    @Test fun anOlderHostHidesWhatItDoesNotRead() {
        val known = Nx2Android.hostKnows(host("docked=%d", "overlay=%d", "rumble=%d", "DM_VK_SETSPLIT", "driver="))
        assertEquals(setOf("docked", "overlay", "rumble", "DM_VK_SETSPLIT", "driver"), known)
    }

    @Test fun aHostWithEverythingShowsEverything() {
        val known = Nx2Android.hostKnows(host(*(Nx2Android.settings.map { it.marker } + "driver=").toTypedArray()))
        assertEquals(Nx2Android.settings.map { it.key }.toSet() + "driver", known)
    }

    private fun manifest(vararg languages: String) = org.json.JSONObject().put("pagurin", org.json.JSONObject().put("languages", org.json.JSONArray(languages.toList())))
    private fun language(m: org.json.JSONObject) = Nx2Android.gameSettings(m).firstOrNull { it.key == "DM_LANG" }

    @Test fun onlyTheGamesLanguagesAreOffered() {
        assertEquals(listOf("en-US", "ja", "ko"), language(manifest("ko", "en-US", "ja", "xx"))!!.choices.map { it.value })   // in the control data's order; an unknown code dropped
    }

    @Test fun noLanguageRowWithoutAChoice() {
        assertEquals(null, language(manifest("en-US")))
        assertEquals(null, language(org.json.JSONObject()))   // languages not known: a port taken from an installed app
        assertEquals(Nx2Android.settings.size - 1, Nx2Android.gameSettings(org.json.JSONObject()).size)   // the rest stays
    }

    @Test fun theAppsLanguageElseEnglishElseTheFirst() {
        val game = language(manifest("en-US", "en-GB", "ja", "ru", "ko"))!!
        assertEquals("ko", pickLanguage(game, "ko"))
        assertEquals("en-US", pickLanguage(game, "pt"))   // a language the game lacks
        assertEquals("ja", pickLanguage(language(manifest("ja", "zh-Hans"))!!, "ko"))   // no English either: the game's first
    }

    @Test fun everyDefaultIsDeclared() {
        Nx2Android.settings.forEach { s -> assertTrue(s.key, s.choices.any { it.value == Nx2Android.hostDefault(s.key) }) }
    }
}

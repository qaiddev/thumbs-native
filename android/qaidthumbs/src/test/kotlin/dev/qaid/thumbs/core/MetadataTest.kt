package dev.qaid.thumbs.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val device = DeviceInfo(
    osVersion = "15", model = "Google Pixel 8", appName = "CinemaCrew", appVersion = "1.4",
    build = "812", locale = "en-US", screenWidth = 412, screenHeight = 915,
)

class MetadataTest {
    @Test fun builtInKeysStayFlat() {
        val meta = ReportMetadata.build(device, "Errands", "native")
        assertEquals(
            setOf("platform", "osVersion", "device", "app", "appVersion", "build", "locale", "sdk", "source", "screen"),
            meta.keys().asSequence().toSet(),
        )
        assertEquals("Google Pixel 8", meta.getString("device"))
        assertEquals("qaid-android/0.3.0", meta.getString("sdk"))
        assertFalse(ReportMetadata.build(device, null, "native").has("screen"))
        assertFalse(ReportMetadata.build(device, "", "native").has("screen"))
    }

    @Test fun userAndCustomNest() {
        val meta = ReportMetadata.build(device, null, "native", QaidUser(id = "u1", email = "a@b.c"), mapOf("plan" to "pro"))
        val user = meta.getJSONObject("user")
        assertEquals("u1", user.getString("id"))
        assertEquals("a@b.c", user.getString("email"))
        assertFalse(user.has("name"))
        assertEquals("pro", meta.getJSONObject("custom").getString("plan"))
    }

    @Test fun emptyUserAndCustomAreLeftOut() {
        val meta = ReportMetadata.build(device, null, "native", QaidUser(id = " ", email = null, name = ""), emptyMap())
        assertFalse(meta.has("user"))
        assertFalse(meta.has("custom"))
        assertNull(QaidUser().cleaned())
        assertEquals(QaidUser(name = "Ann"), QaidUser(id = "", name = " Ann ").cleaned())
    }
}

class CustomMetadataTest {
    @Test fun setReplaceAndRemove() {
        val custom = CustomMetadata()
        custom.set("plan", "free")
        custom.set("plan", "pro")
        custom.set("team", "red")
        assertEquals(mapOf("plan" to "pro", "team" to "red"), custom.snapshot())
        custom.set("team", null)
        assertEquals(mapOf("plan" to "pro"), custom.snapshot())
        custom.clear()
        assertTrue(custom.snapshot().isEmpty())
    }

    @Test fun keysPastThirtyAreIgnoredButOldOnesStillChange() {
        val custom = CustomMetadata()
        for (i in 1..35) custom.set("k$i", "v$i")
        val snap = custom.snapshot()
        assertEquals(30, snap.size)
        assertFalse(snap.containsKey("k31"))
        custom.set("k1", "changed")
        assertEquals("changed", custom.snapshot()["k1"])
        custom.set("k2", null)
        custom.set("k99", "fits now")
        assertEquals("fits now", custom.snapshot()["k99"])
    }

    @Test fun keysAndValuesAreCut() {
        val custom = CustomMetadata()
        custom.set("k".repeat(80), "v".repeat(600))
        val (key, value) = custom.snapshot().entries.single()
        assertEquals(64, key.length)
        assertEquals(500, value.length)
        custom.set("   ", "blank key")
        assertEquals(1, custom.snapshot().size)
    }
}

class QaidTextTest {
    @Test fun englishDefaults() {
        val text = QaidText()
        assertEquals("Send feedback", text.title)
        assertEquals("to the CinemaCrew team", text.subtitle("CinemaCrew"))
        assertEquals("Sending…", text.sending)
        assertEquals("Saved. It will send when you're back online.", text.queued)
        assertEquals("Circle what matters, or black out anything private. Use keeps the marks; Back drops them.", text.markupHelp)
        assertEquals(listOf("Rectangle", "Arrow", "Pen", "Redact", "Undo", "Clear"),
            listOf(text.markupRectangle, text.markupArrow, text.markupPen, text.markupRedact, text.markupUndo, text.markupClear))
        // Same keys and words as iOS.
        assertEquals("Couldn't add the marks. Try Use again.", text.markupFailed)
        assertEquals(listOf("Discard this feedback?", "Discard", "Keep editing"),
            listOf(text.discardTitle, text.discardConfirm, text.discardCancel))
    }

    @Test fun appNameIsSubstituted() {
        assertEquals("pour l'équipe CinemaCrew", QaidText(subtitle = "pour l'équipe {app}").subtitle("CinemaCrew"))
        assertEquals("no token", QaidText(subtitle = "no token").subtitle("X"))
        assertEquals("X and X", QaidText(subtitle = "{app} and {app}").subtitle("X"))
    }

    @Test fun swatchNumberIsSubstituted() {
        assertEquals("Colour 3", QaidText().markupColor(3))
        assertEquals("Farbe 1", QaidText(markupColor = "Farbe {n}").markupColor(1))
    }
}

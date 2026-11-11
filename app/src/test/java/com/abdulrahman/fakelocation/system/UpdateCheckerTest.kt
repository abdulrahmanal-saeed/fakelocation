package com.abdulrahman.fakelocation.system

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private fun release(body: String, vararg assets: String) = JSONObject()
        .put("tag_name", "latest-build")
        .put("body", body)
        .put("assets", JSONArray(assets.map {
            JSONObject().put("name", it).put("browser_download_url", "https://github.com/o/r/releases/download/latest-build/$it")
        }))

    @Test
    fun readsTheNotesCiWrites() {
        val update = UpdateChecker.parse(
            release(
                "Built from main @ 1a2b3c4 · versionName 2.3.0 · versionCode 19. Download FakeLocation.apk on your phone and install it.",
                "FakeLocation.apk", "FakeLocation-release.apk",
            )
        )!!
        assertEquals(19L, update.versionCode)
        assertEquals("2.3.0", update.versionName)
        // The debug APK people install, not the R8 one.
        assertEquals("https://github.com/o/r/releases/download/latest-build/FakeLocation.apk", update.apkUrl)
    }

    @Test
    fun olderNotesWithoutAVersionOfferNothing() {
        // What the release said before CI added the version.
        assertNull(UpdateChecker.parse(release("Built from ccr-bf158032-44k6ny @ 5a330c3. Download FakeLocation.apk on your phone and install it.", "FakeLocation.apk")))
    }

    @Test
    fun noApkNoUpdate() {
        assertNull(UpdateChecker.parse(release("versionName 2.3.0 · versionCode 19", "something-else.zip")))
        assertNull(UpdateChecker.parse(JSONObject().put("message", "Not Found")))
    }
}

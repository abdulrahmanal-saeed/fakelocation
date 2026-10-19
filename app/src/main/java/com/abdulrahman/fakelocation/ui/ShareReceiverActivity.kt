package com.abdulrahman.fakelocation.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives places shared from Google Maps (or geo: / maps links) and hands them to the map. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getStringExtra(Intent.EXTRA_TEXT)
            ).joinToString("\n")
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) startActivity(MainActivity.withSharedText(this, text))
        finish()
    }
}

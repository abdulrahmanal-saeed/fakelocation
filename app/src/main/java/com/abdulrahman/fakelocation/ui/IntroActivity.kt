package com.abdulrahman.fakelocation.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.google.android.material.button.MaterialButton

class IntroActivity : AppCompatActivity() {
    private data class Page(val icon: Int, val title: Int, val detail: Int)

    private val pages = listOf(
        Page(R.drawable.ic_pin, R.string.onboarding_pin_title, R.string.onboarding_pin_body),
        Page(R.drawable.ic_route, R.string.onboarding_route_title, R.string.onboarding_route_body),
        Page(R.drawable.ic_navigate, R.string.onboarding_drive_title, R.string.onboarding_drive_body),
    )
    private var index = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        index = state?.getInt("intro_page") ?: 0
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("intro_page", index)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val page = pages[index]
        val inset = (28 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, inset, inset, inset)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        scroll.padForSystemBars(top = true, bottom = true)
        fun label(value: Int, style: Int) = TextView(this).apply {
            setTextAppearance(style)
            setText(value)
            gravity = Gravity.CENTER
        }
        fun button(value: Int, action: () -> Unit) = MaterialButton(this).apply {
            setText(value)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(label(R.string.app_name, R.style.Text_Heading))
        content.addView(ImageView(this).apply {
            setImageResource(page.icon)
            setColorFilter(androidx.core.content.ContextCompat.getColor(this@IntroActivity, if (index == 2) R.color.real else R.color.action))
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(inset * 3, inset * 3).apply { topMargin = inset; bottomMargin = inset })
        content.addView(label(page.title, R.style.Text_Title))
        content.addView(label(page.detail, R.style.Text_Item), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = inset / 2
            bottomMargin = inset
        })
        if (index == pages.lastIndex) {
            button(R.string.account_sign_in) { complete(openAccount = true) }
            button(R.string.onboarding_guest) { complete() }
        } else {
            button(R.string.onboarding_next) { index++; render() }
            button(R.string.onboarding_skip) { complete() }
        }
    }

    private fun complete(openAccount: Boolean = false) {
        val prefs = Prefs(this)
        val first = !prefs.setupDone
        prefs.setupDone = true
        if (first) {
            WhatsNew.markSeen(this)
            startActivity(Intent(this, MainActivity::class.java).putExtras(intent))
        }
        if (openAccount) startActivity(Intent(this, AccountActivity::class.java))
        finish()
    }
}

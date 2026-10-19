package com.abdulrahman.fakelocation.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.databinding.ActivityAlarmBinding
import com.abdulrahman.fakelocation.mock.MockLocationService
import java.util.Locale

/** Full-screen alarm shown over the lock screen: "arriving at C in 2:00" or "leaving B in 1:00". */
class AlarmActivity : AppCompatActivity() {

    enum class Kind { ARRIVING, LEAVING }

    private lateinit var binding: ActivityAlarmBinding
    private val handler = Handler(Looper.getMainLooper())
    private var target = 0L

    private val countdown = object : Runnable {
        override fun run() {
            val left = ((target - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            binding.countdown.text = String.format(Locale.US, "%d:%02d", left / 60, left % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        enableEdgeToEdge()
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)
        bind(intent)
        binding.dismissButton.setOnClickListener {
            MockLocationService.dismissAlarm(this)
            finish()
        }
        ContextCompat.registerReceiver(this, closeReceiver, IntentFilter(ACTION_CLOSE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        val kind = Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.ARRIVING.name)
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        target = intent.getLongExtra(EXTRA_TARGET, System.currentTimeMillis())
        binding.overline.setText(if (kind == Kind.ARRIVING) R.string.alarm_overline_arriving else R.string.alarm_overline_leaving)
        binding.untilText.setText(if (kind == Kind.ARRIVING) R.string.alarm_until_reach else R.string.alarm_until_leave)
        binding.letter.text = stopLetter(index)
        binding.letter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, stopColorRes(index, count)))
        binding.name.text = intent.getStringExtra(EXTRA_NAME)
        val note = intent.getStringExtra(EXTRA_NOTE)
        binding.note.text = note
        binding.note.isVisible = !note.isNullOrBlank()
        handler.removeCallbacks(countdown)
        handler.post(countdown)
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdown)
        unregisterReceiver(closeReceiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_CLOSE = "com.abdulrahman.fakelocation.CLOSE_ALARM"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_NOTE = "note"

        fun intent(context: Context, kind: Kind, index: Int, count: Int, name: String, target: Long, note: String?) =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_NOTE, note)
    }
}

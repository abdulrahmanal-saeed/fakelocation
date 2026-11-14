package com.abdulrahman.fakelocation.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.plus.PlusAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun AppCompatActivity.withDualModeAccess(overlap: Boolean, start: () -> Unit) {
    if (!overlap || PlusAccess.mayUseBoth(this)) {
        start()
        return
    }
    lifecycleScope.launch {
        val allowed = try {
            PlusAccess.unlock(this@withDualModeAccess)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (allowed) start() else startActivity(Intent(this@withDualModeAccess, PlusActivity::class.java))
    }
}

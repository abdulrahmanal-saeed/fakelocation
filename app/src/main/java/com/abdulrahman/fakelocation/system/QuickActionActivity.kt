package com.abdulrahman.fakelocation.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.mock.LocationMocker
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.ui.MainActivity
import com.abdulrahman.fakelocation.ui.hasLocationPermission

/**
 * Invisible activity used by the tile and the widget to turn the fake location on.
 * Starting it from the foreground is what lets Android start the location service.
 */
class QuickActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val place = QuickActions.defaultPlace(this)
        when {
            place == null || !hasLocationPermission() || !LocationMocker.isSelectedMockApp(this) ->
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> {
                MockLocationService.teleport(this, place.position, place.name ?: place.position.format())
                Toast.makeText(this, getString(R.string.notification_title, place.name), Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        fun intent(context: Context) =
            Intent(context, QuickActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

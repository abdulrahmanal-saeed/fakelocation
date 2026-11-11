package com.abdulrahman.fakelocation.ui

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.geo.Router
import com.abdulrahman.fakelocation.geo.RoutingServers
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Routing servers" on the Map & routing page: an own OSRM / Valhalla server, tried before the public ones. */
class ServerSettings(private val fragment: Fragment, private val binding: () -> FragmentSettingsBinding?) {

    private val context get() = fragment.requireContext()
    private val prefs get() = Prefs(context)

    fun setUp() {
        val b = binding() ?: return
        b.osrmRow.setOnClickListener {
            edit(R.string.server_osrm, prefs.osrmServer) { prefs.osrmServer = it; check(osrm = it) }
        }
        b.valhallaRow.setOnClickListener {
            edit(R.string.server_valhalla, prefs.valhallaServer) { prefs.valhallaServer = it; check(valhalla = it) }
        }
    }

    fun render() {
        val b = binding() ?: return
        b.osrmValue.text = RoutingServers.label(prefs.osrmServer) ?: context.getString(R.string.server_public)
        b.valhallaValue.text = RoutingServers.label(prefs.valhallaServer) ?: context.getString(R.string.server_public)
    }

    private fun edit(title: Int, current: String, onSave: (String) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = context.getString(R.string.server_hint)
            setText(current)
            setSingleLine()
        }
        val frame = FrameLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (!RoutingServers.isValid(text)) {
                    Toast.makeText(context, R.string.server_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                onSave(RoutingServers.normalize(text).orEmpty())
                render()
            }
            .setNeutralButton(R.string.server_use_public) { _, _ -> onSave(""); render() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A short drive in Dubai through the server just saved, so a typo shows up now and not on the road. */
    private fun check(osrm: String = "", valhalla: String = "") {
        if (osrm.isBlank() && valhalla.isBlank()) return
        val app = context.applicationContext
        val stops = listOf(LatLon(25.1972, 55.2744), LatLon(25.2048, 55.2708))
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if (osrm.isNotBlank()) Router.osrm(RoutingServers.osrm(osrm, TravelMode.CAR).first(), stops)
                    else Router.valhalla(RoutingServers.valhalla(valhalla).first(), stops, TravelMode.CAR)
                }.exceptionOrNull()
            }
            val text = if (error == null) app.getString(R.string.server_ok)
            else app.getString(R.string.server_failed, error.message ?: error.javaClass.simpleName)
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }
}

package com.abdulrahman.fakelocation.ui

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.MapProvider
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.GooglePlaces
import com.abdulrahman.fakelocation.geo.google.GoogleRoutes
import com.abdulrahman.fakelocation.geo.google.GoogleTiles
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Google Maps (optional)" part of Settings: the API key, what to use Google for, and a key check. */
class GoogleSettings(private val fragment: Fragment, private val binding: FragmentSettingsBinding, private val onChange: () -> Unit) {

    private val context get() = fragment.requireContext()
    private val prefs get() = Prefs(context)

    fun setUp() {
        binding.googleKeyRow.setOnClickListener { editKey() }
        binding.googleMapRow.setOnClickListener { chooseMap() }
        binding.googleRoutesRow.setOnClickListener {
            choose(R.string.google_routes, listOf(R.string.routes_free, R.string.routes_google), if (prefs.useGoogleRoutes) 1 else 0) {
                prefs.useGoogleRoutes = it == 1
            }
        }
        binding.googleSearchRow.setOnClickListener {
            choose(R.string.google_search, listOf(R.string.search_free, R.string.search_google), if (prefs.useGoogleSearch) 1 else 0) {
                prefs.useGoogleSearch = it == 1
            }
        }
        binding.googleTestRow.setOnClickListener { testKey() }
        binding.googleHowRow.setOnClickListener { showHowTo() }
    }

    fun render() {
        val p = prefs
        binding.googleKeyValue.text = if (p.hasGoogleKey) context.getString(R.string.google_key_set, p.googleApiKey.takeLast(4))
        else context.getString(R.string.google_key_none)
        binding.googleMapValue.setText(mapLabel(if (p.hasGoogleKey) p.mapProvider else MapProvider.OSM))
        binding.googleRoutesValue.setText(if (p.useGoogleRoutes && p.hasGoogleKey) R.string.routes_google else R.string.routes_free)
        binding.googleSearchValue.setText(if (p.useGoogleSearch && p.hasGoogleKey) R.string.search_google else R.string.search_free)
    }

    private fun mapLabel(provider: MapProvider) = when (provider) {
        MapProvider.OSM -> R.string.map_osm
        MapProvider.GOOGLE_ROADMAP -> R.string.map_google_roadmap
        MapProvider.GOOGLE_SATELLITE -> R.string.map_google_satellite
        MapProvider.GOOGLE_HYBRID -> R.string.map_google_hybrid
    }

    private fun editKey() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = context.getString(R.string.google_key_hint)
            setText(prefs.googleApiKey)
            setSingleLine()
        }
        val frame = FrameLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.google_key_title)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.googleApiKey = input.text.toString()
                prefs.googleTileSession = null
                if (prefs.hasGoogleKey) context.toast(R.string.google_key_saved)
                changed()
            }
            .setNeutralButton(R.string.remove) { _, _ ->
                prefs.googleApiKey = ""
                prefs.googleTileSession = null
                changed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseMap() {
        if (!prefs.hasGoogleKey) {
            context.toast(R.string.google_needs_key)
            editKey()
            return
        }
        val options = MapProvider.entries
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.google_map_style)
            .setSingleChoiceItems(options.map { context.getString(mapLabel(it)) }.toTypedArray(), options.indexOf(prefs.mapProvider)) { dialog, which ->
                prefs.mapProvider = options[which]
                changed()
                dialog.dismiss()
            }
            .show()
    }

    private fun choose(title: Int, labels: List<Int>, current: Int, onPick: (Int) -> Unit) {
        if (!prefs.hasGoogleKey) {
            context.toast(R.string.google_needs_key)
            editKey()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.map { context.getString(it) }.toTypedArray(), current) { dialog, which ->
                onPick(which)
                changed()
                dialog.dismiss()
            }
            .show()
    }

    private fun changed() {
        render()
        onChange()
    }

    /** Calls each Google API once and shows which ones work and Google's message for the ones that don't. */
    private fun testKey() {
        if (!prefs.hasGoogleKey) {
            context.toast(R.string.google_needs_key)
            editKey()
            return
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.google_test_title)
            .setMessage(R.string.google_testing)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        val ctx = context.applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val api = GoogleApi(ctx)
            val a = LatLon(30.0444, 31.2357)
            val b = LatLon(30.0459, 31.2243)
            val checks = withContext(Dispatchers.IO) {
                listOf(
                    R.string.api_tiles to async { GoogleTiles.session(ctx, api, MapProvider.GOOGLE_ROADMAP) },
                    R.string.api_routes to async { GoogleRoutes.route(api, listOf(a, b), TravelMode.CAR) },
                    R.string.api_places to async { GooglePlaces.search(api, "Cairo Tower", a) },
                    R.string.api_geocoding to async { GooglePlaces.reverse(api, a) },
                ).map { (name, job) -> name to runCatching { job.await() }.exceptionOrNull() }
            }
            val text = checks.joinToString("\n\n") { (name, error) ->
                ctx.getString(name) + "\n" + (error?.let { "✗ " + it.message } ?: ctx.getString(R.string.google_ok))
            }
            dialog.setMessage(text)
            prefs.googleTileSession = null
            changed()
        }
    }

    private fun showHowTo() {
        val sha1 = GoogleApi.signatureSha1(context).chunked(2).joinToString(":")
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.google_how)
            .setMessage(context.getString(R.string.google_how_body, context.packageName, sha1))
            .setPositiveButton(R.string.open_console) { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/google/maps-apis/credentials")))
            }
            .setNeutralButton(R.string.copy_sha1) { _, _ ->
                context.copyToClipboard(sha1)
                context.toast(R.string.copied)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

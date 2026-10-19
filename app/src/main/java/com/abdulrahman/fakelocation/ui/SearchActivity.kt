package com.abdulrahman.fakelocation.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.databinding.ActivitySearchBinding
import com.abdulrahman.fakelocation.geo.LocationText
import com.abdulrahman.fakelocation.geo.MapsLinkResolver
import com.abdulrahman.fakelocation.geo.PlaceSearch
import com.abdulrahman.fakelocation.mock.MockState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search by name, coordinates or a pasted maps link. Returns the chosen place. */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var prefs: Prefs
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)
        prefs = Prefs(this)

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener { binding.searchInput.text = null }
        binding.searchInput.doAfterTextChanged { onQueryChanged(it?.toString().orEmpty(), immediate = false) }
        binding.searchInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onQueryChanged(view.text.toString(), immediate = true)
                hideKeyboard()
                true
            } else {
                false
            }
        }

        supportFragmentManager.setFragmentResultListener(CoordinatesSheet.RESULT_KEY, this) { _, result ->
            finishWith(CoordinatesSheet.placeFrom(result))
        }

        binding.quickList.showRows(
            listOf(
                PlaceRow("coords", getString(R.string.enter_coordinates), getString(R.string.enter_coordinates_hint),
                    icon = R.drawable.ic_hash, tinted = false) { CoordinatesSheet.show(supportFragmentManager) },
                PlaceRow("link", getString(R.string.paste_link), getString(R.string.paste_link_hint),
                    icon = R.drawable.ic_link, tinted = false) { pasteLink() },
            )
        )
        showRecent()

        binding.searchInput.requestFocus()
        binding.searchInput.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showRecent() {
        val recent = PlacesRepository.get(this).history.value.distinctBy { it.name }.take(6)
        binding.recentSection.isVisible = recent.isNotEmpty()
        binding.recentList.showRows(recent.map { entry ->
            PlaceRow("h${entry.id}", entry.name, historySubtitle(entry), icon = R.drawable.ic_history, tinted = false) {
                finishWith(Place(entry.position, entry.name))
            }
        })
    }

    private fun pasteLink() {
        val text = clipboardText()
        if (text == null) {
            toast(R.string.clipboard_empty)
            return
        }
        binding.searchInput.setText(text)
        binding.searchInput.setSelection(binding.searchInput.length())
        onQueryChanged(text, immediate = true)
    }

    private fun onQueryChanged(raw: String, immediate: Boolean) {
        val query = raw.trim()
        binding.clearButton.isVisible = query.isNotEmpty()
        searchJob?.cancel()
        val idle = query.isEmpty()
        binding.resultsSection.isVisible = !idle
        binding.quickSection.isVisible = idle
        binding.recentSection.isVisible = idle && PlacesRepository.get(this).history.value.isNotEmpty()
        if (idle) return

        LocationText.parseCoordinates(query)?.let { position ->
            showResults(listOf(Place(position, getString(R.string.coordinates_result), position.format())))
            return
        }
        val isLink = LocationText.extractUrl(query) != null || query.startsWith("geo:")
        if (!isLink && query.length < 2) return

        searchJob = lifecycleScope.launch {
            if (!immediate && !isLink) delay(SEARCH_DELAY_MS)
            showStatus(if (isLink) R.string.reading_link else R.string.searching)
            val results = runCatching {
                if (isLink) listOfNotNull(MapsLinkResolver.resolve(query)) else PlaceSearch.search(this@SearchActivity, query, nearPoint())
            }
            results.onSuccess { places ->
                if (places.isEmpty()) showStatus(if (isLink) R.string.link_failed else R.string.no_results) else showResults(places)
            }.onFailure {
                showStatus(R.string.search_error)
            }
        }
    }

    private fun nearPoint(): LatLon? = MockState.session.value?.position ?: prefs.lastRealLocation

    private fun showStatus(text: Int) {
        binding.resultsList.removeAllViews()
        binding.resultsStatus.isVisible = true
        binding.resultsStatus.setText(text)
    }

    private fun showResults(places: List<Place>) {
        binding.resultsStatus.isVisible = false
        val real = prefs.lastRealLocation
        binding.resultsList.showRows(places.mapIndexed { i, place ->
            PlaceRow(
                "r$i", place.name ?: place.position.format(), place.address,
                trailing = real?.let { formatDistance(distanceMeters(it, place.position)) }
            ) { finishWith(place) }
        })
    }

    private fun finishWith(place: Place) {
        hideKeyboard()
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_LAT, place.position.lat)
                .putExtra(EXTRA_LON, place.position.lon)
                .putExtra(EXTRA_NAME, place.name.takeUnless { it == getString(R.string.coordinates_result) })
                .putExtra(EXTRA_ADDRESS, place.address.takeUnless { it == place.position.format() })
        )
        finish()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    companion object {
        private const val SEARCH_DELAY_MS = 600L
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ADDRESS = "address"

        fun placeFrom(data: Intent?): Place? {
            if (data == null || !data.hasExtra(EXTRA_LAT)) return null
            return Place(
                LatLon(data.getDoubleExtra(EXTRA_LAT, 0.0), data.getDoubleExtra(EXTRA_LON, 0.0)),
                data.getStringExtra(EXTRA_NAME),
                data.getStringExtra(EXTRA_ADDRESS)
            )
        }
    }
}

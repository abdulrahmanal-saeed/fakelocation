package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.databinding.SheetCoordinatesBinding
import com.abdulrahman.fakelocation.geo.LocationText
import com.abdulrahman.fakelocation.geo.MapsLinkResolver
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.Locale

/** Type or paste coordinates (or a maps link). Hands the place back as a fragment result. */
class CoordinatesSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCoordinatesBinding? = null
    private val binding get() = _binding!!
    private var pastedName: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCoordinatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.latInput.doAfterTextChanged { binding.error.isVisible = false }
        binding.lonInput.doAfterTextChanged { binding.error.isVisible = false }
        binding.lonInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.pasteButton.setOnClickListener { paste() }
        binding.showButton.setOnClickListener { submit() }
        binding.latInput.requestFocus()
    }

    private fun paste() {
        val text = requireContext().clipboardText()
        if (text == null) {
            requireContext().toast(R.string.clipboard_empty)
            return
        }
        LocationText.parseCoordinates(text)?.let {
            fill(it)
            return
        }
        binding.pasteButton.isEnabled = false
        binding.pasteButton.setText(R.string.reading_link)
        viewLifecycleOwner.lifecycleScope.launch {
            val place = runCatching { MapsLinkResolver.resolve(text) }.getOrNull()
            if (_binding == null) return@launch
            binding.pasteButton.isEnabled = true
            binding.pasteButton.setText(R.string.paste_clipboard)
            if (place == null) {
                requireContext().toast(R.string.link_failed)
            } else {
                pastedName = place.name
                fill(place.position)
            }
        }
    }

    private fun fill(position: LatLon) {
        binding.latInput.setText(String.format(Locale.US, "%.6f", position.lat))
        binding.lonInput.setText(String.format(Locale.US, "%.6f", position.lon))
    }

    private fun submit() {
        val lat = binding.latInput.text?.toString()?.replace(',', '.')?.trim()?.toDoubleOrNull()
        val lon = binding.lonInput.text?.toString()?.replace(',', '.')?.trim()?.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            binding.error.isVisible = true
            return
        }
        setFragmentResult(RESULT_KEY, bundleOf("lat" to lat, "lon" to lon, "name" to pastedName))
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "coordinates"

        fun show(fragmentManager: FragmentManager) = CoordinatesSheet().show(fragmentManager, "coordinates")

        fun placeFrom(result: Bundle) =
            Place(LatLon(result.getDouble("lat"), result.getDouble("lon")), result.getString("name"))
    }
}

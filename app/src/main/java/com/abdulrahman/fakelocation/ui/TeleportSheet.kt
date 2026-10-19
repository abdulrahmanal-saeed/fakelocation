package com.abdulrahman.fakelocation.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.databinding.SheetTeleportBinding
import com.abdulrahman.fakelocation.geo.PlaceSearch
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/** Bottom sheet for one place: its address, coordinates, favorite star and "Teleport here". */
class TeleportSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTeleportBinding? = null
    private val binding get() = _binding!!
    private lateinit var position: LatLon
    private var name: String? = null
    private var address: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetTeleportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        position = LatLon(args.getDouble(ARG_LAT), args.getDouble(ARG_LON))
        name = args.getString(ARG_NAME)
        address = args.getString(ARG_ADDRESS)
        val places = PlacesRepository.get(requireContext())

        binding.coords.text = position.format()
        renderText()
        if (name == null || address == null) lookUpAddress()

        binding.copyButton.setOnClickListener {
            requireContext().copyToClipboard(position.format(6))
            requireContext().toast(R.string.coordinates_copied)
        }
        binding.favoriteButton.setOnClickListener {
            val existing = places.favoriteAt(position)
            if (existing != null) {
                places.deleteFavorite(existing.id)
                requireContext().toast(R.string.favorite_removed)
            } else {
                requireContext().askName(R.string.favorite_name_title, name.orEmpty()) { chosen ->
                    places.addFavorite(chosen, position)
                    requireContext().toast(R.string.favorite_added)
                }
            }
        }
        binding.navigateHereButton.setOnClickListener {
            startActivity(NavigateActivity.intent(requireContext(), Place(position, name ?: position.format(), address)))
            dismiss()
        }
        binding.addToRouteButton.setOnClickListener {
            (activity as? MainActivity)?.addToRoute(Place(position, name, address))
            dismiss()
        }
        binding.teleportButton.setOnClickListener {
            (activity as? MainActivity)?.teleport(Place(position, name ?: position.format(), address))
            dismiss()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                places.favorites.collect {
                    val saved = places.favoriteAt(position)
                    binding.favoriteButton.setIconResource(if (saved != null) R.drawable.ic_star_filled else R.drawable.ic_star)
                    binding.favoriteButton.contentDescription =
                        getString(if (saved != null) R.string.remove_favorite else R.string.save_favorite)
                    if (saved != null && name == null) {
                        name = saved.name
                        renderText()
                    }
                }
            }
        }
    }

    private fun lookUpAddress() {
        viewLifecycleOwner.lifecycleScope.launch {
            val found = runCatching { PlaceSearch.reverse(requireContext(), position) }.getOrNull()
            if (name == null) name = found?.name
            if (address == null) address = found?.address
            if (_binding != null) renderText(lookupDone = true)
        }
    }

    private fun renderText(lookupDone: Boolean = false) {
        binding.title.text = name ?: getString(R.string.dropped_pin)
        val distance = Prefs(requireContext()).lastRealLocation?.let {
            getString(R.string.from_you, formatDistance(distanceMeters(it, position)))
        }
        val area = address ?: if (lookupDone || name != null) null else getString(R.string.finding_address)
        binding.subtitle.text = listOfNotNull(area, distance).joinToString(" · ")
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MainActivity)?.mapFragment?.clearSelection()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "teleport"
        private const val ARG_LAT = "lat"
        private const val ARG_LON = "lon"
        private const val ARG_NAME = "name"
        private const val ARG_ADDRESS = "address"

        fun show(fragmentManager: FragmentManager, place: Place) {
            (fragmentManager.findFragmentByTag(TAG) as? TeleportSheet)?.dismissAllowingStateLoss()
            TeleportSheet().apply {
                arguments = bundleOf(
                    ARG_LAT to place.position.lat,
                    ARG_LON to place.position.lon,
                    ARG_NAME to place.name,
                    ARG_ADDRESS to place.address,
                )
            }.show(fragmentManager, TAG)
        }
    }
}

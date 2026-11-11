package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Favorite
import com.abdulrahman.fakelocation.data.HistoryEntry
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.databinding.FragmentPlacesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PlacesFragment : Fragment(R.layout.fragment_places) {

    private var _binding: FragmentPlacesBinding? = null
    private val binding get() = _binding!!
    private lateinit var places: PlacesRepository
    private val adapter: PlaceRowAdapter = PlaceRowAdapter { holder -> dragger.startDrag(holder) }
    private val showingHistory = MutableStateFlow(false)
    private val main get() = requireActivity() as MainActivity
    private var dragFrom = -1
    private var dragTo = -1

    /** Favorites only: drag by the handle to change the order (the first ones are the map's chips and shortcuts). */
    private val dragger: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            if (dragFrom < 0) dragFrom = a
            dragTo = b
            adapter.submitList(adapter.currentList.toMutableList().apply { add(b, removeAt(a)) })
            return true
        }

        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(rv, holder)
            if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
                places.moveFavorite(dragFrom, dragTo)
                holder.itemView.confirmHaptic()
            }
            dragFrom = -1
            dragTo = -1
        }

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
    })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPlacesBinding.bind(view)
        binding.root.padForSystemBars(top = true)
        places = PlacesRepository.get(requireContext())
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        dragger.attachToRecyclerView(binding.list)

        binding.tabs.addOnButtonCheckedListener { _, id, checked ->
            if (checked) showingHistory.value = id == R.id.tabHistory
        }
        binding.footerButton.setOnClickListener {
            if (showingHistory.value) clearHistory() else main.openSearchForFavorite()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(showingHistory, places.favorites, places.history) { h, f, hist -> Triple(h, f, hist) }
                    .collect { (history, favorites, entries) -> render(history, favorites, entries) }
            }
        }
    }

    fun showHistory() {
        _binding?.tabs?.check(R.id.tabHistory)
    }

    private fun render(history: Boolean, favorites: List<Favorite>, entries: List<HistoryEntry>) {
        val rows = if (history) entries.map { historyRow(it) } else favorites.map { favoriteRow(it) }
        adapter.submitList(rows)
        binding.empty.isVisible = rows.isEmpty()
        binding.empty.setText(if (history) R.string.no_history else R.string.no_favorites)
        binding.footerButton.setText(if (history) R.string.clear_history else R.string.add_favorite)
        binding.footerButton.setIconResource(if (history) R.drawable.ic_trash else R.drawable.ic_plus)
        binding.footerButton.isVisible = !history || entries.isNotEmpty()
    }

    private fun favoriteRow(favorite: Favorite) = PlaceRow(
        key = "f${favorite.id}",
        title = favorite.name,
        subtitle = favorite.position.format(),
        initial = favorite.name.take(1).uppercase(),
        onGo = { main.teleport(Place(favorite.position, favorite.name)) },
        onLongClick = { editFavorite(favorite) },
        draggable = places.favorites.value.size > 1,
    ) { main.showPlace(Place(favorite.position, favorite.name)) }

    private fun historyRow(entry: HistoryEntry): PlaceRow {
        val saved = places.favoriteAt(entry.position) != null
        return PlaceRow(
            key = "h${entry.id}",
            title = entry.name,
            subtitle = requireContext().historySubtitle(entry),
            icon = R.drawable.ic_history,
            tinted = false,
            starred = saved,
            onStar = { toggleFavorite(entry) },
        ) { main.showPlace(Place(entry.position, entry.name)) }
    }

    private fun toggleFavorite(entry: HistoryEntry) {
        val existing = places.favoriteAt(entry.position)
        if (existing != null) {
            places.deleteFavorite(existing.id)
            requireContext().toast(R.string.favorite_removed)
        } else {
            requireContext().askName(R.string.favorite_name_title, entry.name) { name ->
                places.addFavorite(name, entry.position)
                requireContext().toast(R.string.favorite_added)
            }
        }
    }

    private fun editFavorite(favorite: Favorite) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(favorite.name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    requireContext().askName(R.string.rename, favorite.name) { places.renameFavorite(favorite.id, it) }
                } else {
                    deleteFavorite(favorite)
                }
            }
            .show()
    }

    /** Deleted straight away, with Undo for a few seconds. */
    private fun deleteFavorite(favorite: Favorite) {
        val index = places.favorites.value.indexOfFirst { it.id == favorite.id }
        places.deleteFavorite(favorite.id)
        Snackbar.make(binding.root, getString(R.string.favorite_deleted, favorite.name), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { places.restoreFavorite(favorite, index) }
            .show()
    }

    private fun clearHistory() {
        val cleared = places.history.value
        places.clearHistory()
        Snackbar.make(binding.root, R.string.history_cleared, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { places.restoreHistory(cleared) }
            .show()
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }
}

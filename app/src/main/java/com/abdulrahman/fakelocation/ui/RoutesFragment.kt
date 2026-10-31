package com.abdulrahman.fakelocation.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.databinding.FragmentRoutesBinding
import com.abdulrahman.fakelocation.databinding.ItemRouteBinding
import com.abdulrahman.fakelocation.geo.Gpx
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoutesFragment : Fragment(R.layout.fragment_routes) {

    private var _binding: FragmentRoutesBinding? = null
    private val binding get() = _binding!!
    private val main get() = requireActivity() as MainActivity
    private val adapter = RoutesAdapter()
    private var exporting: Route? = null

    // Files apps often call GPX/KML "application/octet-stream", so any file can be picked and is checked after.
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importFrom(uri) }
    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val route = exporting
        if (uri != null && route != null) exportTo(uri, route)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentRoutesBinding.bind(view)
        binding.root.padForSystemBars(top = true)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.newRouteButton.setOnClickListener { main.openRouteEditor() }
        binding.importButton.setOnClickListener { openFile.launch(arrayOf("*/*")) }

        val repo = RoutesRepository.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.routes.collect { routes ->
                    adapter.submitList(routes)
                    binding.empty.isVisible = routes.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    /** Long-press on a route: export it, or delete it. */
    private fun showRouteMenu(route: Route) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(route.name)
            .setItems(arrayOf(getString(R.string.export_gpx), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    exporting = route
                    createFile.launch(route.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".gpx")
                } else {
                    deleteRoute(route)
                }
            }
            .show()
    }

    /** Deleted straight away, with Undo for a few seconds. */
    private fun deleteRoute(route: Route) {
        val repo = RoutesRepository.get(requireContext())
        repo.delete(route.id)
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, getString(R.string.route_deleted, route.name), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAnchorView(binding.newRouteButton)
            .setAction(R.string.undo) { repo.restore(route) }
            .show()
    }

    private fun importFrom(uri: Uri) {
        val app = requireContext().applicationContext
        val names = Triple(getString(R.string.imported_route), getString(R.string.import_start), getString(R.string.import_end))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Gpx.toRoute(Gpx.read(text), RoutesRepository.newId(), names.first, names.second, names.third)
                }
            }
            result.onSuccess { route ->
                RoutesRepository.get(app).save(route)
                app.toast(R.string.import_done)
                main.openRouteEditor(route.id)
            }.onFailure {
                Log.w("RoutesFragment", "import failed", it)
                app.toast(if (it is Gpx.NotATrackException) R.string.import_not_a_track else R.string.import_failed)
            }
        }
    }

    private fun exportTo(uri: Uri, route: Route) {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Gpx.write(route, System.currentTimeMillis()).toByteArray()) }
                }.onFailure { Log.w("RoutesFragment", "export failed", it) }.isSuccess
            }
            app.toast(if (saved) R.string.export_done else R.string.export_failed)
        }
    }

    private inner class RoutesAdapter : ListAdapter<Route, RoutesAdapter.Holder>(Diff) {
        inner class Holder(val binding: ItemRouteBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val route = getItem(position)
            val context = holder.binding.root.context
            with(holder.binding) {
                name.text = route.name
                meta.text = context.routeMeta(route)
                updated.text = context.editedText(route.updatedAt)
                thumb.setRoute(route)
                root.setOnClickListener { main.openRouteEditor(route.id) }
                root.setOnLongClickListener { showRouteMenu(route); true }
                editButton.setOnClickListener { main.openRouteEditor(route.id) }
                timingButton.isEnabled = route.path != null && route.stops.size >= 2
                timingButton.setOnClickListener { main.openRoutePlan(route.id) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Route>() {
        override fun areItemsTheSame(a: Route, b: Route) = a.id == b.id
        override fun areContentsTheSame(a: Route, b: Route) = a == b
    }
}

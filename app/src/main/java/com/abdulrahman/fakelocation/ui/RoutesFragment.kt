package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class RoutesFragment : Fragment(R.layout.fragment_routes) {

    private var _binding: FragmentRoutesBinding? = null
    private val binding get() = _binding!!
    private val main get() = requireActivity() as MainActivity
    private val adapter = RoutesAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentRoutesBinding.bind(view)
        binding.root.padForSystemBars(top = true)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.newRouteButton.setOnClickListener { main.openRouteEditor() }

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

    private fun confirmDelete(route: Route) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_route_title, route.name))
            .setPositiveButton(R.string.delete) { _, _ -> RoutesRepository.get(requireContext()).delete(route.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                root.setOnLongClickListener { confirmDelete(route); true }
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

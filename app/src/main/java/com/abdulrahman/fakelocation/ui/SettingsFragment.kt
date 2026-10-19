package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view)
        binding.root.padForSystemBars(top = true)
        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        binding.versionText.text = info.versionName
        binding.setupRow.setOnClickListener { (requireActivity() as MainActivity).openSetup() }
    }
}

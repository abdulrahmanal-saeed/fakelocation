package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.abdulrahman.fakelocation.R

/** Placeholder until the route editor lands in the next phase. */
class RoutesFragment : Fragment(R.layout.fragment_routes) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.padForSystemBars(top = true)
    }
}

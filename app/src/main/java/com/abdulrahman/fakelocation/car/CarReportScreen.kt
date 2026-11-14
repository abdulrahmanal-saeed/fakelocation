package com.abdulrahman.fakelocation.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.RoadReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class CarReportScreen(carContext: CarContext) : Screen(carContext) {
    private var nearby: RoadReports.Incident? = null
    private var busy = false

    init {
        lifecycleScope.launch {
            val fix = RoadReports.fix(NavSession.state.value) ?: return@launch
            nearby = try {
                RoadReports.nearby(carContext, fix).firstOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { null }
            if (nearby != null) invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        for (kind in RoadReports.Kind.entries) {
            items.addItem(GridItem.Builder().setTitle(carContext.getString(kind.label))
                .setImage(CarUi.icon(carContext, R.drawable.ic_road_report))
                .setOnClickListener { report(kind) }.build())
        }
        nearby?.let { report ->
            val votes = if (report.confirmed) listOf("present", "gone") else listOf("present")
            for (vote in votes) {
                val label = if (vote == "present") R.string.report_vote_present else R.string.report_vote_gone
                val title = carContext.getString(report.kind.label) + " · " + carContext.getString(label)
                items.addItem(GridItem.Builder().setTitle(title)
                    .setImage(CarUi.icon(carContext, R.drawable.ic_road_report))
                    .setOnClickListener { vote(report, vote) }.build())
            }
        }
        val grid = GridTemplate.Builder().setTitle(carContext.getString(R.string.report_road))
            .setHeaderAction(Action.BACK).setSingleList(items.build()).build()
        return if (carContext.carAppApiLevel >= 7) MapWithContentTemplate.Builder()
            .setContentTemplate(grid).build() else grid
    }

    private fun report(kind: RoadReports.Kind) = submit {
        when (RoadReports.submit(carContext, kind, RoadReports.fix(NavSession.state.value))) {
            RoadReports.Result.SENT -> R.string.report_saved
            RoadReports.Result.DRAFT -> R.string.report_saved_draft
            RoadReports.Result.SIGN_IN -> R.string.report_login
        }
    }

    private fun vote(report: RoadReports.Incident, vote: String) = submit {
        val fix = RoadReports.fix(NavSession.state.value) ?: return@submit R.string.nav_location_needed
        RoadReports.vote(carContext, fix, report.id, vote)
        R.string.report_saved
    }

    private fun submit(send: suspend () -> Int) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val message = try {
                send()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { R.string.account_failure }
            CarToast.makeText(carContext, carContext.getString(message), CarToast.LENGTH_SHORT).show()
            screenManager.pop()
        }
    }
}

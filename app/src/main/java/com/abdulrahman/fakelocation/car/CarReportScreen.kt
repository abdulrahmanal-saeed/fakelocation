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
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        for (kind in RoadReports.Kind.entries) {
            items.addItem(GridItem.Builder().setTitle(carContext.getString(kind.label))
                .setImage(CarUi.icon(carContext, R.drawable.ic_road_report))
                .setOnClickListener { report(kind) }.build())
        }
        val grid = GridTemplate.Builder().setTitle(carContext.getString(R.string.report_road))
            .setHeaderAction(Action.BACK).setSingleList(items.build()).build()
        return if (carContext.carAppApiLevel >= 7) MapWithContentTemplate.Builder()
            .setContentTemplate(grid).build() else grid
    }

    private fun report(kind: RoadReports.Kind) {
        lifecycleScope.launch {
            val message = try {
                when (RoadReports.submit(carContext, kind, RoadReports.fix(NavSession.state.value))) {
                    RoadReports.Result.SENT -> R.string.report_saved
                    RoadReports.Result.DRAFT -> R.string.report_saved_draft
                    RoadReports.Result.SIGN_IN -> R.string.report_login
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { R.string.account_failure }
            CarToast.makeText(carContext, carContext.getString(message), CarToast.LENGTH_SHORT).show()
            screenManager.pop()
        }
    }
}

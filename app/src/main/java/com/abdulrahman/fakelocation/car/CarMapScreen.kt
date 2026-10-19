package com.abdulrahman.fakelocation.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.nav.NavSession
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** The car's home: the map, with search/places/nearby while idle and turn-by-turn guidance while navigating. */
class CarMapScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {

    private val navigationManager = carContext.getCarService(NavigationManager::class.java)
    private var hostKnowsNavigating = false
    private var shownKey = ""

    init {
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = NavSession.stop()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavSession.state.collect { onState(it) }
            }
        }
    }

    private fun onState(s: NavSession.State) {
        if (s.navigating != hostKnowsNavigating) {
            hostKnowsNavigating = s.navigating
            if (s.navigating) navigationManager.navigationStarted() else navigationManager.navigationEnded()
        }
        // Only rebuild the template when something on it changes (hosts throttle template updates).
        val p = s.progress
        val key = listOf(s.status, s.muted, s.rerouting, p?.arrived, p?.nextStep?.text, p?.thenStep?.text,
            p?.metersToNextStep?.let { (it / 10).roundToInt() }, p?.remainingSeconds?.let { (it / 30).roundToInt() }).toString()
        if (key != shownKey) {
            shownKey = key
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val s = NavSession.state.value
        val builder = NavigationTemplate.Builder()
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(iconAction(R.drawable.ic_plus) { renderer.zoomIn() })
                    .addAction(iconAction(R.drawable.ic_minus) { renderer.zoomOut() })
                    .build(),
            )

        if (!s.navigating) {
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setTitle(carContext.getString(R.string.car_search))
                            .setIcon(CarUi.icon(carContext, R.drawable.ic_search))
                            .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }.build(),
                    )
                    .addAction(iconAction(R.drawable.ic_star) { screenManager.push(CarSavedScreen(carContext)) })
                    .addAction(iconAction(R.drawable.ic_cat_fuel) { screenManager.push(CarCategoriesScreen(carContext)) })
                    .build(),
            )
            return builder.build()
        }

        builder.setActionStrip(
            ActionStrip.Builder()
                .addAction(
                    Action.Builder().setTitle(carContext.getString(R.string.nav_end))
                        .setOnClickListener { NavSession.stop() }.build(),
                )
                .addAction(iconAction(if (s.muted) R.drawable.ic_volume_off else R.drawable.ic_volume) { NavSession.toggleMute() })
                .addAction(iconAction(R.drawable.ic_plus) { screenManager.push(CarCategoriesScreen(carContext)) })
                .build(),
        )

        val p = s.progress
        when {
            p != null && p.arrived -> builder.setNavigationInfo(
                MessageInfo.Builder(carContext.getString(R.string.nav_arrived))
                    .apply { s.to?.name?.let { setText(it) } }
                    .setImage(CarUi.icon(carContext, R.drawable.ic_m_arrive)).build(),
            )
            p == null || s.rerouting || p.nextStep == null ->
                builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            else -> {
                val routing = RoutingInfo.Builder()
                    .setCurrentStep(CarUi.step(carContext, p.nextStep), CarUi.distance(p.metersToNextStep))
                p.thenStep?.let { routing.setNextStep(CarUi.step(carContext, it)) }
                builder.setNavigationInfo(routing.build())
                builder.setDestinationTravelEstimate(
                    TravelEstimate.Builder(CarUi.distance(p.remainingMeters), ZonedDateTime.now().plusSeconds(p.remainingSeconds.toLong()))
                        .setRemainingTimeSeconds(p.remainingSeconds.toLong().coerceAtLeast(0))
                        .build(),
                )
            }
        }
        return builder.build()
    }

    private fun iconAction(icon: Int, onClick: () -> Unit) =
        Action.Builder().setIcon(CarUi.icon(carContext, icon)).setOnClickListener(onClick).build()
}

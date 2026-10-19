package com.abdulrahman.fakelocation.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.abdulrahman.fakelocation.nav.NavIcons
import com.abdulrahman.fakelocation.nav.NavStep
import kotlin.math.roundToInt
import com.abdulrahman.fakelocation.nav.Maneuver as Turn

/** Turning our routes and icons into Android Auto's building blocks. */
object CarUi {

    /** One of our vector icons as a white bitmap icon for the car. */
    fun icon(context: CarContext, res: Int, color: Int = 0xFFFFFFFF.toInt(), sizePx: Int = 96): CarIcon {
        val themed = android.view.ContextThemeWrapper(context, com.abdulrahman.fakelocation.R.style.Theme_FakeLocation)
        val drawable = ContextCompat.getDrawable(themed, res)!!.mutate()
        drawable.setTint(color)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    fun distance(meters: Double): Distance = when {
        meters >= 10_000 -> Distance.create((meters / 1000).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
        meters >= 1000 -> Distance.create((meters / 100).roundToInt() / 10.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create(((meters / 10).roundToInt() * 10).toDouble().coerceAtLeast(0.0), Distance.UNIT_METERS)
    }

    /** "12 min · 6.2 km" text that Android Auto formats itself. */
    fun durationText(seconds: Double): CharSequence =
        SpannableString(" ").apply { setSpan(DurationSpan.create(seconds.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }

    fun distanceText(meters: Double): CharSequence =
        SpannableString(" ").apply { setSpan(DistanceSpan.create(distance(meters)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }

    fun step(context: CarContext, step: NavStep, lanes: List<com.abdulrahman.fakelocation.nav.Lane> = step.lanes): Step {
        val builder = Step.Builder(step.text).setManeuver(maneuver(context, step))
        if (step.road.isNotBlank()) builder.setRoad(step.road)
        if (lanes.isNotEmpty()) {
            for (lane in lanes) {
                val shape = laneShape(NavIcons.laneDirection(lane, step.maneuver))
                builder.addLane(Lane.Builder().addDirection(LaneDirection.create(shape, lane.valid)).build())
            }
            val image = NavIcons.lanesImage(context, lanes, step.maneuver)
            builder.setLanesImage(CarIcon.Builder(IconCompat.createWithBitmap(image)).build())
        }
        return builder.build()
    }

    private fun maneuver(context: CarContext, step: NavStep): Maneuver {
        // The UAE drives on the right, so roundabouts go anticlockwise and U-turns are to the left.
        val type = when (step.maneuver) {
            Turn.DEPART -> Maneuver.TYPE_DEPART
            Turn.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            Turn.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            Turn.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            Turn.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            Turn.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            Turn.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            Turn.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            Turn.UTURN -> Maneuver.TYPE_U_TURN_LEFT
            Turn.ROUNDABOUT -> if (step.exit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            Turn.ARRIVE -> Maneuver.TYPE_DESTINATION
        }
        val builder = Maneuver.Builder(type).setIcon(icon(context, NavIcons.maneuverIcon(step.maneuver)))
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) builder.setRoundaboutExitNumber(step.exit)
        return builder.build()
    }

    private fun laneShape(direction: String) = when (direction) {
        "left" -> LaneDirection.SHAPE_NORMAL_LEFT
        "right" -> LaneDirection.SHAPE_NORMAL_RIGHT
        "slight left" -> LaneDirection.SHAPE_SLIGHT_LEFT
        "slight right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "sharp left" -> LaneDirection.SHAPE_SHARP_LEFT
        "sharp right" -> LaneDirection.SHAPE_SHARP_RIGHT
        "uturn" -> LaneDirection.SHAPE_U_TURN_LEFT
        else -> LaneDirection.SHAPE_STRAIGHT
    }
}

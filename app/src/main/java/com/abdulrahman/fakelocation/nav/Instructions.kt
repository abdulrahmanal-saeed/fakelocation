package com.abdulrahman.fakelocation.nav

/** Turns router maneuvers into short English instructions (screen text + what the voice says). */
object Instructions {

    data class Instruction(val maneuver: Maneuver, val text: String, val spoken: String)

    const val ARRIVE_TEXT = "You have arrived"

    fun fromOsrm(type: String, modifier: String, name: String, exit: Int, ref: String = ""): Instruction {
        val road = name.ifBlank { ref }
        val onto = if (road.isNotBlank()) " onto $road" else ""
        val on = if (road.isNotBlank()) " on $road" else ""
        val turn = turnFor(modifier)
        val side = sideWord(modifier)
        return when (type) {
            "depart" -> Instruction(Maneuver.DEPART, "Head off$on", "Start driving")
            "arrive" -> Instruction(Maneuver.ARRIVE, ARRIVE_TEXT, ARRIVE_TEXT)
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> {
                val nth = if (exit > 0) ordinal(exit) else "next"
                Instruction(Maneuver.ROUNDABOUT, "At the roundabout, take the $nth exit$onto", "At the roundabout, take the $nth exit")
            }
            "roundabout turn" -> Instruction(turn, "At the roundabout, turn $side$onto", "At the roundabout, turn $side")
            "fork" -> Instruction(turn, "Keep $side$onto", "Keep $side")
            "merge" -> Instruction(turn, "Merge $side$onto", "Merge $side")
            "on ramp" -> Instruction(turn, "Take the ramp$onto", "Take the ramp on the $side")
            "off ramp" -> Instruction(turn, "Take the exit$onto", "Take the exit on the $side")
            "end of road" -> Instruction(turn, "At the end of the road, turn $side$onto", "At the end of the road, turn $side")
            "continue", "new name" ->
                if (turn == Maneuver.STRAIGHT) Instruction(Maneuver.STRAIGHT, "Continue$onto", "Continue straight")
                else Instruction(turn, "${verb(turn)}$onto", verb(turn))
            else -> Instruction(turn, "${verb(turn)}$onto", verb(turn))
        }
    }

    fun fromGoogle(maneuver: String, text: String): Instruction {
        val kind = when (maneuver) {
            "TURN_LEFT" -> Maneuver.LEFT
            "TURN_RIGHT" -> Maneuver.RIGHT
            "TURN_SLIGHT_LEFT", "FORK_LEFT", "RAMP_LEFT", "MERGE" -> Maneuver.SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT", "FORK_RIGHT", "RAMP_RIGHT" -> Maneuver.SLIGHT_RIGHT
            "TURN_SHARP_LEFT" -> Maneuver.SHARP_LEFT
            "TURN_SHARP_RIGHT" -> Maneuver.SHARP_RIGHT
            "UTURN_LEFT", "UTURN_RIGHT" -> Maneuver.UTURN
            "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> Maneuver.ROUNDABOUT
            "DEPART" -> Maneuver.DEPART
            else -> Maneuver.STRAIGHT
        }
        val clean = text.replace("\n", ", ").ifBlank { verb(kind) }
        return Instruction(kind, clean, if (isLatin(clean)) clean else verb(kind))
    }

    private fun turnFor(modifier: String) = when (modifier) {
        "left" -> Maneuver.LEFT
        "right" -> Maneuver.RIGHT
        "slight left" -> Maneuver.SLIGHT_LEFT
        "slight right" -> Maneuver.SLIGHT_RIGHT
        "sharp left" -> Maneuver.SHARP_LEFT
        "sharp right" -> Maneuver.SHARP_RIGHT
        "uturn" -> Maneuver.UTURN
        else -> Maneuver.STRAIGHT
    }

    private fun sideWord(modifier: String) = when {
        "left" in modifier -> "left"
        "right" in modifier -> "right"
        else -> "straight"
    }

    fun verb(m: Maneuver) = when (m) {
        Maneuver.LEFT -> "Turn left"
        Maneuver.RIGHT -> "Turn right"
        Maneuver.SLIGHT_LEFT -> "Bear left"
        Maneuver.SLIGHT_RIGHT -> "Bear right"
        Maneuver.SHARP_LEFT -> "Turn sharp left"
        Maneuver.SHARP_RIGHT -> "Turn sharp right"
        Maneuver.UTURN -> "Make a U-turn"
        Maneuver.ROUNDABOUT -> "Enter the roundabout"
        Maneuver.ARRIVE -> ARRIVE_TEXT
        Maneuver.DEPART -> "Start driving"
        Maneuver.STRAIGHT -> "Continue straight"
    }

    private fun ordinal(n: Int) = when (n) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; 6 -> "sixth"
        else -> "${n}th"
    }

    private fun isLatin(text: String) = text.none { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC }
}

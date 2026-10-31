package com.abdulrahman.fakelocation.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One real drive (or walk) made with in-app navigation. */
data class Drive(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val destination: String,
    val mode: TravelMode,
    val meters: Double,
    val tollsAed: Double,
    val tollGates: Int,
    val fuelAed: Double,
) {
    val seconds get() = (endedAt - startedAt) / 1000.0
    val averageKmh get() = if (seconds > 0) meters / seconds * 3.6 else 0.0
}

/** A month of drives added up. */
data class MonthTotal(
    val month: YearMonth,
    val drives: Int,
    val meters: Double,
    val seconds: Double,
    val tollsAed: Double,
    val fuelAed: Double,
)

/** Every navigation that went somewhere, newest first, kept in a small JSON file. */
class DriveLog private constructor(private val file: File) {

    private val _drives = MutableStateFlow<List<Drive>>(emptyList())
    val drives: StateFlow<List<Drive>> = _drives

    init {
        if (file.exists()) {
            try {
                _drives.value = JSONArray(file.readText()).let { arr -> (0 until arr.length()).map { read(arr.getJSONObject(it)) } }
            } catch (e: Exception) {
                Log.w("DriveLog", "could not read drives", e)
            }
        }
    }

    @Synchronized
    fun add(drive: Drive) {
        _drives.value = (listOf(drive) + _drives.value).take(MAX_DRIVES)
        save()
    }

    @Synchronized
    fun clear() {
        _drives.value = emptyList()
        save()
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(_drives.value.map(::write)).toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val MAX_DRIVES = 1000

        /** Shorter than this isn't a drive (opened navigation and closed it again). */
        const val MIN_METERS = 300.0

        @Volatile
        private var instance: DriveLog? = null

        fun get(context: Context): DriveLog =
            instance ?: synchronized(this) {
                instance ?: DriveLog(File(context.applicationContext.filesDir, "drives.json")).also { instance = it }
            }

        fun write(d: Drive): JSONObject = JSONObject()
            .put("id", d.id).put("start", d.startedAt).put("end", d.endedAt).put("to", d.destination).put("mode", d.mode.name)
            .put("m", d.meters).put("tolls", d.tollsAed).put("gates", d.tollGates).put("fuel", d.fuelAed)

        fun read(j: JSONObject) = Drive(
            j.getLong("id"), j.getLong("start"), j.getLong("end"), j.optString("to"),
            runCatching { TravelMode.valueOf(j.getString("mode")) }.getOrDefault(TravelMode.CAR),
            j.optDouble("m", 0.0), j.optDouble("tolls", 0.0), j.optInt("gates", 0), j.optDouble("fuel", 0.0),
        )

        /** Totals per calendar month (in [zone]), newest month first. */
        fun months(drives: List<Drive>, zone: ZoneId): List<MonthTotal> =
            drives.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) }
                .map { (month, list) ->
                    MonthTotal(month, list.size, list.sumOf { it.meters }, list.sumOf { it.seconds }, list.sumOf { it.tollsAed }, list.sumOf { it.fuelAed })
                }
                .sortedByDescending { it.month }
    }
}

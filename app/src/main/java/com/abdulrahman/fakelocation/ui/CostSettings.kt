package com.abdulrahman.fakelocation.ui

import android.text.InputType
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.databinding.DialogNameBinding
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.drive.Fuel
import com.abdulrahman.fakelocation.drive.FuelType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** The "Car & costs" settings: fuel grade, price per litre, consumption, plate and parking alarm. */
class CostSettings(private val fragment: Fragment, private val binding: FragmentSettingsBinding) {

    private val context get() = fragment.requireContext()
    private val prefs get() = Prefs(context)

    fun setUp() {
        binding.fuelTypeRow.setOnClickListener {
            val types = FuelType.entries
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_fuel_type)
                .setSingleChoiceItems(types.map { context.getString(it.label) }.toTypedArray(), types.indexOf(prefs.fuelType)) { d, which ->
                    prefs.fuelType = types[which]
                    render()
                    d.dismiss()
                }
                .show()
        }
        binding.fuelPriceRow.setOnClickListener {
            val type = prefs.fuelType
            ask(R.string.settings_fuel_price, String.format(Locale.US, "%.2f", prefs.fuelPrice(type)), decimal = true,
                neutral = R.string.settings_reset_price to { prefs.setFuelPrice(type, null) }) { text ->
                text.toDoubleOrNull()?.takeIf { it in 0.5..20.0 }?.let { prefs.setFuelPrice(type, it) }
            }
        }
        binding.consumptionRow.setOnClickListener {
            ask(R.string.settings_consumption, String.format(Locale.US, "%.1f", prefs.fuelConsumption), decimal = true) { text ->
                text.toDoubleOrNull()?.takeIf { it in 2.0..40.0 }?.let { prefs.fuelConsumption = it }
            }
        }
        binding.plateRow.setOnClickListener {
            ask(R.string.settings_plate, prefs.plateNumber, decimal = false) { prefs.plateNumber = it.uppercase(Locale.US) }
        }
        binding.parkingAlertRow.setOnClickListener {
            val options = listOf(5, 10, 15, 20, 30)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_parking_alert)
                .setSingleChoiceItems(options.map { context.getString(R.string.settings_parking_alert_value, it) }.toTypedArray(),
                    options.indexOf(prefs.parkingAlertMinutes)) { d, which ->
                    prefs.parkingAlertMinutes = options[which]
                    render()
                    d.dismiss()
                }
                .show()
        }
    }

    fun render() {
        val p = prefs
        binding.fuelTypeValue.setText(p.fuelType.label)
        binding.fuelPriceValue.text = context.getString(R.string.settings_fuel_price_value, p.fuelPrice())
        binding.fuelPriceHint.text = context.getString(R.string.settings_fuel_price_hint, Fuel.PRICES_MONTH)
        binding.consumptionValue.text = context.getString(R.string.settings_consumption_value, p.fuelConsumption)
        binding.plateValue.text = p.plateNumber.ifBlank { context.getString(R.string.settings_plate_none) }
        binding.parkingAlertValue.text = context.getString(R.string.settings_parking_alert_value, p.parkingAlertMinutes)
    }

    private fun ask(title: Int, current: String, decimal: Boolean, neutral: Pair<Int, () -> Unit>? = null, onSave: (String) -> Unit) {
        val dialog = DialogNameBinding.inflate(LayoutInflater.from(context))
        dialog.nameLayout.hint = context.getString(title)
        dialog.nameInput.inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        dialog.nameInput.setText(current)
        dialog.nameInput.setSelection(current.length)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(dialog.root)
            .setPositiveButton(R.string.save) { _, _ ->
                onSave(dialog.nameInput.text?.toString()?.trim().orEmpty())
                render()
            }
            .setNegativeButton(R.string.cancel, null)
        neutral?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action(); render() } }
        builder.show()
    }
}

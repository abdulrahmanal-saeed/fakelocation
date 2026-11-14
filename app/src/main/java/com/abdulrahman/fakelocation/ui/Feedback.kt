package com.abdulrahman.fakelocation.ui

import android.text.InputFilter
import android.text.InputType
import android.util.Patterns
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.geo.WayShiftApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal object Feedback {
    fun show(fragment: Fragment) {
        val context = fragment.requireContext()
        if (!WayShiftApi.configured) {
            context.toast(R.string.feedback_not_configured)
            return
        }
        val labels = listOf(R.string.feedback_speed, R.string.feedback_map, R.string.feedback_idea, R.string.feedback_other)
        val categories = listOf("speed", "map", "idea", "other")
        val category = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels.map(context::getString))
            contentDescription = context.getString(R.string.feedback)
        }
        val message = EditText(context).apply {
            hint = context.getString(R.string.feedback_details)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(2_000))
        }
        val email = EditText(context).apply {
            hint = context.getString(R.string.feedback_reply)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            filters = arrayOf(InputFilter.LengthFilter(254))
        }
        val position = Prefs(context).lastRealLocation
        val attach = CheckBox(context).apply { text = context.getString(R.string.feedback_location) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, 0)
            addView(category)
            addView(message)
            addView(email)
            if (position != null) addView(attach)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.feedback)
            .setView(content).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.feedback_send, null).show()
        val submit = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        val id = UUID.randomUUID().toString()
        submit.setOnClickListener {
            val reply = email.text.toString().trim()
            val details = message.text.toString().trim()
            if (details.length < 5) {
                message.error = context.getString(R.string.feedback_details)
                return@setOnClickListener
            }
            if (reply.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(reply).matches()) {
                email.error = context.getString(R.string.feedback_reply)
                return@setOnClickListener
            }
            val data = JSONObject().put("id", id).put("category", categories[category.selectedItemPosition])
                .put("message", details).put("email", reply)
            if (attach.isChecked && position != null) data.put("lat", position.lat).put("lon", position.lon)
            submit.isEnabled = false
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val ticket = WayShiftApi.request("/v1/feedback", data, com.abdulrahman.fakelocation.account.WayShiftAccount.token(context)).getString("id")
                    dialog.dismiss()
                    MaterialAlertDialogBuilder(context)
                        .setMessage(context.getString(R.string.feedback_saved, ticket))
                        .setPositiveButton(android.R.string.ok, null).show()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (dialog.isShowing) {
                        submit.isEnabled = true
                        context.toast(R.string.feedback_retry)
                    }
                }
            }
        }
    }
}

package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.account.WayShiftAccount
import com.abdulrahman.fakelocation.account.awaitResult
import com.abdulrahman.fakelocation.account.CloudBackup
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AccountActivity : AppCompatActivity() {
    private lateinit var layout: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        render()
    }

    private fun render() {
        val inset = (24 * resources.displayMetrics.density).toInt()
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
        scroll.padForSystemBars(top = true, bottom = true)
        button(R.string.onboarding_guest) { finish() }
        layout.addView(TextView(this).apply {
            setText(R.string.account)
            setTextAppearance(R.style.Text_Heading)
        })
        val auth = WayShiftAccount.auth(this)
        if (auth == null) {
            layout.addView(TextView(this).apply { setText(R.string.account_unavailable) })
            return
        }
        val user = auth.currentUser
        if (user == null) {
            val email = field(R.string.account_email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val password = field(R.string.account_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            fun address() = email.text.toString().trim()
            if (WayShiftAccount.googleAvailable) button(R.string.account_google) {
                perform { auth.signInWithCredential(WayShiftAccount.googleCredential(this)).awaitResult(); render() }
            }
            button(R.string.account_sign_in) {
                perform { auth.signInWithEmailAndPassword(address(), password.text.toString()).awaitResult(); render() }
            }
            button(R.string.account_create) {
                perform {
                    val created = auth.createUserWithEmailAndPassword(address(), password.text.toString()).awaitResult().user
                    render()
                    created?.sendEmailVerification()?.awaitResult()
                    toast(R.string.account_sent)
                }
            }
            button(R.string.account_reset) {
                perform { auth.sendPasswordResetEmail(address()).awaitResult(); toast(R.string.account_sent) }
            }
            return
        }
        layout.addView(TextView(this).apply { text = user.email.orEmpty() })
        if (!user.isEmailVerified) {
            layout.addView(TextView(this).apply { setText(R.string.account_verified_needed) })
            button(R.string.account_verify) { perform { user.sendEmailVerification().awaitResult(); toast(R.string.account_sent) } }
        } else {
            button(R.string.account_sync) { CloudBackup.show(this, user) }
            button(R.string.account_sync_automatic) { CloudBackup.showAutoSync(this, user) }
        }
        button(R.string.account_sign_out) { perform { WayShiftAccount.signOut(this, auth); render() } }
        button(R.string.account_delete) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.account_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.account_delete) { _, _ ->
                    perform {
                        user.reload().awaitResult()
                        val info = user.getIdToken(true).awaitResult()
                        if (System.currentTimeMillis() / 1000 - info.authTimestamp >= 300) {
                            toast(R.string.account_delete_reauth)
                            return@perform
                        }
                        CloudBackup.delete(this, user)
                        user.delete().awaitResult()
                        render()
                    }
                }.show()
        }
    }

    private fun button(label: Int, action: () -> Unit) {
        layout.addView(MaterialButton(this).apply {
            setText(label)
            setOnClickListener { if (!busy) action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun field(label: Int, type: Int) = EditText(this).apply {
        hint = getString(label)
        inputType = type
        isSingleLine = true
        this@AccountActivity.layout.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: FirebaseAuthRecentLoginRequiredException) {
                toast(R.string.account_delete_reauth)
            } catch (_: Exception) {
                toast(R.string.account_failure)
            } finally {
                busy = false
            }
        }
    }
}

package com.abdulrahman.fakelocation.plus

import android.content.Context
import androidx.core.content.edit
import com.abdulrahman.fakelocation.BuildConfig
import com.abdulrahman.fakelocation.account.WayShiftAccount
import com.abdulrahman.fakelocation.geo.WayShiftApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

internal object PlusAccess {
    data class State(val until: Long, val credits: Int, val session: String?) {
        val paid get() = until > System.currentTimeMillis()
    }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun storage(context: Context) = context.getSharedPreferences("wayshift_plus", Context.MODE_PRIVATE)
    private fun uid(context: Context) = WayShiftAccount.auth(context)?.currentUser?.takeIf { it.isEmailVerified }?.uid
    private fun device(context: Context): String {
        val store = storage(context)
        return store.getString("device", null) ?: UUID.randomUUID().toString().also { store.edit(commit = true) { putString("device", it) } }
    }

    fun cached(context: Context): State {
        val user = uid(context) ?: return State(0, 0, null)
        val store = storage(context)
        return State(store.getLong("until:$user", 0), store.getInt("credits:$user", 0), store.getString("session:$user", null))
    }

    suspend fun refresh(context: Context): State = request(context, "/v1/plus")

    suspend fun request(context: Context, endpoint: String, body: JSONObject? = null): State = lock.withLock {
        val user = uid(context) ?: return@withLock State(0, 0, null)
        val token = WayShiftAccount.token(context) ?: error("Sign-in required")
        val response = WayShiftApi.request(endpoint, body, token)
        check(uid(context) == user) { "Account changed" }
        val active = response.optJSONObject("active")
        val session = if (active?.optString("device") == device(context)) active.optString("id") else null
        val state = State(response.optLong("plusUntil"), response.optInt("credits"), session)
        storage(context).edit(commit = true) {
            putLong("until:$user", state.until)
            putInt("credits:$user", state.credits)
            putString("session:$user", state.session)
        }
        state
    }

    fun mayUseBoth(context: Context): Boolean =
        !BuildConfig.PLAY_BUILD || cached(context).let { it.paid || it.session != null }

    suspend fun unlock(context: Context): Boolean {
        if (!BuildConfig.PLAY_BUILD) return true
        if (uid(context) == null || !WayShiftApi.configured) return false
        retryFinish(context)
        val current = if (mayUseBoth(context)) cached(context) else refresh(context)
        if (current.paid || current.session != null) return true
        if (current.credits < 5) return false
        return request(context, "/v1/plus/session", sessionBody(context, UUID.randomUUID().toString())).session != null
    }

    fun endWhenInactive(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { finish(app) } }
    }

    suspend fun finish(context: Context, release: Boolean = false) {
        val user = uid(context) ?: return
        val session = cached(context).session ?: return
        val store = storage(context)
        val endpoint = if (release) "/v1/plus/session/release" else "/v1/plus/session/end"
        store.edit(commit = true) { putString("pending:$user", "$endpoint|$session"); remove("session:$user") }
        retryFinish(context)
    }

    suspend fun retryFinish(context: Context) {
        val user = uid(context) ?: return
        val store = storage(context)
        val pending = store.getString("pending:$user", null)?.split('|', limit = 2) ?: return
        request(context, pending[0], sessionBody(context, pending[1]))
        store.edit(commit = true) { remove("pending:$user") }
    }

    private fun sessionBody(context: Context, id: String) =
        JSONObject().put("id", id).put("device", device(context))
}

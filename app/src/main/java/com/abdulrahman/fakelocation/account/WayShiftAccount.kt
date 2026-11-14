package com.abdulrahman.fakelocation.account

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.abdulrahman.fakelocation.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object WayShiftAccount {
    fun auth(context: Context): FirebaseAuth? {
        val appContext = context.applicationContext
        if (BuildConfig.FIREBASE_APP_ID.isBlank() || BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
            BuildConfig.FIREBASE_API_KEY.isBlank()) return null
        synchronized(this) {
            val app = FirebaseApp.getApps(appContext).firstOrNull() ?: FirebaseApp.initializeApp(
                appContext,
                FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApiKey(BuildConfig.FIREBASE_API_KEY).build(),
            )
            return FirebaseAuth.getInstance(app)
        }
    }

    val googleAvailable get() = BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()

    suspend fun googleCredential(activity: Activity): com.google.firebase.auth.AuthCredential {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.FIREBASE_WEB_CLIENT_ID).build()
        val result = CredentialManager.create(activity).getCredential(
            activity, GetCredentialRequest.Builder().addCredentialOption(option).build(),
        ).credential
        check(result is CustomCredential && result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        return GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(result.data).idToken, null)
    }

    suspend fun signOut(context: Context, auth: FirebaseAuth) {
        auth.signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }

    suspend fun token(context: Context): String? =
        auth(context)?.currentUser?.getIdToken(false)?.awaitResult()?.token
}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (!cont.isActive) return@addOnCompleteListener
        if (task.isSuccessful) cont.resume(task.result)
        else cont.resumeWithException(task.exception ?: IllegalStateException("Operation did not complete"))
    }
}

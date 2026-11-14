package com.abdulrahman.fakelocation.plus

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.abdulrahman.fakelocation.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

internal object WayShiftAds {
    private var initialized = false
    private var requested = false
    private val callbacks = mutableListOf<() -> Unit>()

    fun consent(activity: Activity, ready: () -> Unit) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        if (initialized && info.canRequestAds()) {
            ready()
            return
        }
        callbacks += ready
        if (requested) return
        requested = true
        fun complete() {
            requested = false
            val listeners = callbacks.toList()
            callbacks.clear()
            if (info.canRequestAds()) {
                if (!initialized) MobileAds.initialize(activity.applicationContext)
                initialized = true
                listeners.forEach { it() }
            }
        }
        info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { complete() }
        }, { complete() })
    }

    fun attachBanner(activity: Activity, slot: FrameLayout, visible: () -> Boolean) {
        if (!BuildConfig.PLAY_BUILD || BuildConfig.ADMOB_BANNER_ID.isBlank() || !visible() || slot.childCount > 0) return
        consent(activity) {
            if (activity.isFinishing || activity.isDestroyed || !visible() || slot.childCount > 0) return@consent
            (slot.parent as View).doOnLayout { parent ->
                if (activity.isFinishing || activity.isDestroyed || !visible() || slot.childCount > 0) return@doOnLayout
                val width = (parent.width / activity.resources.displayMetrics.density).toInt()
                if (width <= 0) return@doOnLayout
                val ad = AdView(activity).apply {
                    adUnitId = BuildConfig.ADMOB_BANNER_ID
                    setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, width))
                    adListener = object : AdListener() {
                        override fun onAdLoaded() { if (visible()) slot.visibility = View.VISIBLE else clearBanner(slot) }
                        override fun onAdFailedToLoad(error: LoadAdError) { clearBanner(slot) }
                    }
                }
                slot.addView(ad)
                ad.loadAd(AdRequest.Builder().build())
            }
        }
    }

    fun clearBanner(slot: FrameLayout) {
        val ad = slot.getChildAt(0) as? AdView
        slot.removeAllViews()
        slot.visibility = View.GONE
        ad?.destroy()
    }
}

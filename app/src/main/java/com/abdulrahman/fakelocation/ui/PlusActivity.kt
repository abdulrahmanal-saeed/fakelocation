package com.abdulrahman.fakelocation.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.BuildConfig
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.account.WayShiftAccount
import com.abdulrahman.fakelocation.geo.WayShiftApi
import com.abdulrahman.fakelocation.plus.PlusAccess
import com.abdulrahman.fakelocation.plus.WayShiftAds
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

class PlusActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var billing: BillingClient? = null
    private var details: ProductDetails? = null
    private var watching = false
    private var connectedPurchase: Purchase? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        render()
        if (BuildConfig.PLAY_BUILD && WayShiftApi.configured) {
            billing = BillingClient.newBuilder(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .setListener { result, items ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) verify(items.orEmpty())
                    else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) toast(R.string.account_failure)
                }.build()
            billing?.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() = Unit
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProducts()
                        restore()
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (WayShiftApi.configured) perform {
            PlusAccess.retryFinish(this)
            PlusAccess.refresh(this)
            render()
        }
    }

    override fun onDestroy() {
        billing?.endConnection()
        super.onDestroy()
    }

    private fun render() {
        val inset = (24 * resources.displayMetrics.density).toInt()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inset,inset,inset,inset) }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        scroll.padForSystemBars(top = true, bottom = true)
        text(getString(R.string.plus_title), R.style.Text_Heading)
        text(getString(R.string.plus_pitch))
        text(getString(R.string.plus_checkout_terms))
        val user = WayShiftAccount.auth(this)?.currentUser
        if (user == null) {
            button(getString(R.string.account_sign_in)) { startActivity(Intent(this, AccountActivity::class.java)) }
            return
        }
        if (!user.isEmailVerified) {
            text(getString(R.string.account_verified_needed))
            return
        }
        val state = PlusAccess.cached(this)
        if (state.paid) text(getString(R.string.plus_active))
        val product = details
        product?.subscriptionOfferDetails?.filter { it.offerId == null }?.sortedBy { it.basePlanId == "monthly" }
            ?.forEach { offer ->
                val plan = when (offer.basePlanId) {
                    "weekly" -> R.string.plus_weekly
                    "monthly" -> R.string.plus_monthly
                    else -> return@forEach
                }
                val phase = offer.pricingPhases.pricingPhaseList.firstOrNull() ?: return@forEach
                if (!state.paid && phase.priceAmountMicros > 0) button(getString(plan, phase.formattedPrice)) {
                    val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
                        .setOfferToken(offer.offerToken).build()
                    val id = MessageDigest.getInstance("SHA-256").digest(user.uid.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    billing?.launchBillingFlow(this, BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(item)).setObfuscatedAccountId(id).build())
                }
            }
        if (!state.paid) {
            if (state.credits >= 5 || state.session != null) text(getString(R.string.plus_ready))
            else if (BuildConfig.ADMOB_REWARDED_ID.isNotBlank() && WayShiftApi.configured) {
                button(getString(R.string.plus_watch, state.credits)) { watch() }
            }
        }
        button(getString(R.string.plus_restore)) { restore() }
        button(getString(R.string.plus_manage)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions")))
        }
    }

    private fun text(value: String, style: Int = R.style.Text_Item) {
        content.addView(TextView(this).apply { text = value; setTextAppearance(style) })
    }

    private fun button(label: String, action: () -> Unit) {
        content.addView(MaterialButton(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun queryProducts() {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId("wayshift-plus")
            .setProductType(BillingClient.ProductType.SUBS).build()
        billing?.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, found ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                details = found.productDetailsList.firstOrNull()
                runOnUiThread { if (!isFinishing) render() }
            }
        }
    }

    private fun restore() {
        billing?.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, items ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) verify(items)
        }
    }

    private fun verify(items: List<Purchase>) {
        perform {
            for (purchase in items.filter { "wayshift-plus" in it.products }) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    connectedPurchase = purchase
                    PlusAccess.request(this, "/v1/plus/purchase", JSONObject().put("token", purchase.purchaseToken))
                } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) toast(R.string.plus_pending)
            }
            PlusAccess.refresh(this)
            render()
        }
    }

    private fun watch() {
        if (watching || com.abdulrahman.fakelocation.nav.NavSession.state.value.navigating ||
            com.abdulrahman.fakelocation.mock.MockState.isFaking) return
        WayShiftAds.consent(this) {
            if (watching || isFinishing) return@consent
            watching = true
            perform {
                try {
                    val token = WayShiftAccount.token(this) ?: error("Sign-in required")
                    val nonce = WayShiftApi.request("/v1/plus/video", JSONObject(), token).getString("nonce")
                    RewardedAd.load(this, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            watching = false
                            toast(R.string.plus_video_unavailable)
                        }
                        override fun onAdLoaded(ad: RewardedAd) {
                            if (isFinishing || isDestroyed) { watching = false; return }
                            ad.setServerSideVerificationOptions(ServerSideVerificationOptions.Builder().setCustomData(nonce).build())
                            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() { watching = false }
                                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                                    watching = false
                                    toast(R.string.plus_video_unavailable)
                                }
                            }
                            ad.show(this@PlusActivity) {
                                val before = PlusAccess.cached(this@PlusActivity).credits
                                perform {
                                    repeat(8) {
                                        if (PlusAccess.refresh(this@PlusActivity).credits > before) {
                                            render()
                                            return@perform
                                        }
                                        delay(1250)
                                    }
                                    render()
                                }
                            }
                        }
                    })
                } catch (error: Exception) {
                    watching = false
                    throw error
                }
            }
        }
    }

    private fun perform(action: suspend () -> Unit) = lifecycleScope.launch {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!isFinishing) toast(R.string.account_failure)
        }
    }
}

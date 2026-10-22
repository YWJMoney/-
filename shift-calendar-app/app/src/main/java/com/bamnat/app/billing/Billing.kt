package com.bamnat.app.billing

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.bamnat.app.appContainer
import com.bamnat.app.tier.ProStore
import com.bamnat.app.tier.SubRecord
import com.bamnat.app.tier.TierApi
import com.bamnat.core.tier.PlaySub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.coroutines.resume

/** [MON-03] The one subscription and its plans as entered in Play Console (계획/1.19 3장). */
object ProductIds {
    const val PRO = "bamnat_pro"
    const val MONTHLY = "monthly"
    const val YEARLY = "yearly"
    const val TRIAL = "trial-1m"
    /** Play's own page for the user's subscriptions, where they cancel [MON-05]. */
    fun manageUrl(packageName: String) = "https://play.google.com/store/account/subscriptions?sku=$PRO&package=$packageName"
}

/**
 * [MON-03] A way to pay for 프로 as Play offers it to this account now: the recurring [price] in the
 * account's currency, its [period] (P1M, P1Y) and whether the month's free trial is open to it.
 */
data class Plan(
    val basePlan: String,
    val price: String,
    val priceMicros: Long,
    val currency: String,
    val period: String,
    val trial: Boolean,
    val offerToken: String,
) {
    val yearly: Boolean get() = basePlan == ProductIds.YEARLY
    /** "월 4,990원" or "연 29,990원", as said beside the button and in the consent. */
    val priceWords: String get() = (if (yearly) "연 " else "월 ") + price
}

enum class BillingState { UNKNOWN, READY, UNAVAILABLE }

/** [MON-03] Play billing as the screens see it; screenshots and tests put a fake in its place. */
interface Billing {
    val plans: StateFlow<List<Plan>>
    val state: StateFlow<BillingState>
    /** Reads the plans and this account's purchases again; acknowledges and checks a new one with the server. */
    suspend fun refresh()
    /** Opens Play's own purchase sheet; false when it could not. */
    fun buy(activity: Activity, plan: Plan): Boolean
}

/** No Play here (a test, a phone without the Play Store): no plans, nothing to buy. */
class NoBilling(plans: List<Plan> = emptyList()) : Billing {
    override val plans: StateFlow<List<Plan>> = MutableStateFlow(plans)
    override val state: StateFlow<BillingState> = MutableStateFlow(if (plans.isEmpty()) BillingState.UNAVAILABLE else BillingState.READY)
    override suspend fun refresh() = Unit
    override fun buy(activity: Activity, plan: Plan): Boolean = false
}

/**
 * [MON-03] Google Play Billing Library 9.1.0. Play on the phone says whether this account holds the
 * subscription; the company server, with the owner's Play key, adds its end date, trial and consent
 * (POST /api/purchases). A purchase is acknowledged here within Play's three days, or Play refunds it.
 */
class PlayBilling(
    context: Context,
    private val store: ProStore,
    private val api: () -> TierApi,
    private val device: () -> String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
) : Billing, PurchasesUpdatedListener {
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    override val plans: StateFlow<List<Plan>> = _plans.asStateFlow()
    private val _state = MutableStateFlow(BillingState.UNKNOWN)
    override val state: StateFlow<BillingState> = _state.asStateFlow()
    private var details: ProductDetails? = null
    private val lock = Mutex()
    /** The plan being bought: Play's purchase says neither the base plan nor whether it began with the trial. */
    @Volatile private var buying: Plan? = null

    override suspend fun refresh() = lock.withLock {
        if (!connect()) {
            _state.value = BillingState.UNAVAILABLE
            return@withLock
        }
        _state.value = BillingState.READY
        loadPlans()
        val (ok, purchases) = queryPurchases()
        if (ok) take(purchases, fromQuery = true)
    }

    override fun buy(activity: Activity, plan: Plan): Boolean {
        val d = details ?: return false
        buying = plan
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(plan.offerToken).build(),
            ))
            .build()
        val result = client.launchBillingFlow(activity, params)
        log("billing_open ${result.responseCode}")
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        log("billing_result ${result.responseCode}")
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { lock.withLock { take(purchases.orEmpty(), fromQuery = false) } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refresh() }
            else -> Unit
        }
    }

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun loadPlans() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(ProductIds.PRO).setProductType(BillingClient.ProductType.SUBS).build(),
        )).build()
        val found = suspendCancellableCoroutine<ProductDetails?> { cont ->
            client.queryProductDetailsAsync(params) { result, list ->
                val d = list.productDetailsList.firstOrNull { it.productId == ProductIds.PRO }
                if (cont.isActive) cont.resume(d.takeIf { result.responseCode == BillingClient.BillingResponseCode.OK })
            }
        } ?: return
        details = found
        _plans.value = plansOf(found)
    }

    private suspend fun queryPurchases(): Pair<Boolean, List<Purchase>> = suspendCancellableCoroutine { cont ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, list ->
            if (cont.isActive) cont.resume((result.responseCode == BillingClient.BillingResponseCode.OK) to list)
        }
    }

    /**
     * The subscription Play reports now. From a full query, none means none (ended, refunded); a
     * purchase still pending payment gives nothing yet.
     */
    private suspend fun take(purchases: List<Purchase>, fromQuery: Boolean) {
        val p = purchases.firstOrNull { ProductIds.PRO in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (p == null) {
            if (fromQuery && purchases.none { ProductIds.PRO in it.products }) store.setSub(null)
            return
        }
        if (!p.isAcknowledged) acknowledge(p)
        val now = clock()
        val before = store.current.sub?.takeIf { it.purchasedAt.toEpochMilli() == p.purchaseTime }
        val bought = buying?.takeIf { !fromQuery }?.also { buying = null }
        var record = SubRecord(
            product = ProductIds.PRO,
            basePlan = bought?.basePlan ?: before?.basePlan,
            state = PlaySub.State.ACTIVE,
            expiry = maxOf(before?.expiry ?: now, now.plus(SubRecord.PHONE_HOLD)),
            trial = bought?.trial ?: before?.trial ?: false,
            autoRenew = p.isAutoRenewing,
            purchasedAt = Instant.ofEpochMilli(p.purchaseTime),
            verified = false,
            checkedAt = now,
            consentAt = before?.consentAt,
            stoppedAt = before?.stoppedAt,
        )
        val checked = try {
            api().let { a -> if (a.enabled) a.purchase(device(), ProductIds.PRO, p.purchaseToken) else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (checked?.verified == true) {
            record = record.copy(
                state = checked.state ?: record.state, expiry = checked.expiry ?: record.expiry, trial = checked.trial,
                autoRenew = checked.autoRenew, verified = true, consentAt = checked.consentAt ?: record.consentAt, stoppedAt = checked.stoppedAt,
            )
        }
        store.setSub(record)
    }

    private suspend fun acknowledge(p: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { result ->
                log("billing_ack ${result.responseCode}")
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    companion object {
        /**
         * One plan per base plan: its recurring price, and the free trial when Play offers it to this
         * account (Play leaves out offers the account is not eligible for).
         */
        fun plansOf(d: ProductDetails): List<Plan> {
            val offers = d.subscriptionOfferDetails.orEmpty()
            return offers.groupBy { it.basePlanId }.mapNotNull { (base, list) ->
                val baseOffer = list.firstOrNull { it.offerId == null } ?: return@mapNotNull null
                val recurring = baseOffer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                val trialOffer = list.firstOrNull { o -> o.offerId != null && o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                Plan(
                    basePlan = base, price = recurring.formattedPrice, priceMicros = recurring.priceAmountMicros,
                    currency = recurring.priceCurrencyCode, period = recurring.billingPeriod,
                    trial = trialOffer != null, offerToken = (trialOffer ?: baseOffer).offerToken,
                )
            }.sortedBy { if (it.yearly) 1 else 0 }
        }
    }
}

/** The plans Play offers now, for a screen. */
@Composable
fun rememberPlans(): List<Plan> {
    val billing = LocalContext.current.appContainer.billing
    val plans by billing.plans.collectAsStateWithLifecycle()
    return plans
}

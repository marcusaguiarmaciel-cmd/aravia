package app.aravia.account

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import app.aravia.BuildConfig
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 100% opt-in 24-hour Premium Pass unlocked by watching 2 Rewarded Ads (Google AdMob).
 * - Zero in-app purchases for now.
 * - Non-personalized ads (`npa = "1"`) by default; zero health data is ever sent to AdMob.
 * - Existing supporters (`account.hasEarlyAccess`) already have full Premium without ads.
 */
object RewardedPremium {
    const val REQUIRED_ADS = 2
    const val PASS_DURATION_MS = 24L * 60L * 60L * 1000L
    private const val PREFS = "aravia_rewarded_premium"

    data class State(
        val adsWatched: Int = 0,
        val passExpiresAtMillis: Long = 0L,
        val loadingAd: Boolean = false,
        val showingAd: Boolean = false,
        val statusMessage: String? = null,
        val error: String? = null,
    ) {
        fun isAdPassActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
            passExpiresAtMillis > nowMillis

        fun remainingMinutes(nowMillis: Long = System.currentTimeMillis()): Int {
            val rem = (passExpiresAtMillis - nowMillis).coerceAtLeast(0L)
            return ((rem + 59_999L) / 60_000L).toInt()
        }

        fun remainingLabel(nowMillis: Long = System.currentTimeMillis()): String {
            val mins = remainingMinutes(nowMillis)
            val h = mins / 60
            val m = mins % 60
            return when {
                mins <= 0 -> "Expirado"
                h > 0 && m > 0 -> "${h}h ${m}min restantes"
                h > 0 -> "${h}h restantes"
                else -> "${m}min restantes"
            }
        }
    }

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var sdkInitialized = false
    private var preloadedAd: RewardedAd? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        reloadState()
    }

    /** True if user has Premium either via supporter account OR an active 24h ad-unlocked pass. */
    fun hasPremium(account: Account.State, nowMillis: Long = System.currentTimeMillis()): Boolean =
        account.hasEarlyAccess || _state.value.isAdPassActive(nowMillis)

    fun refreshExpiry() {
        reloadState()
    }

    private fun reloadState() {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        val expires = p.getLong("pass_expires_at", 0L)
        val grantedAt = p.getLong("pass_granted_at", 0L)
        val grantedElapsed = p.getLong("pass_granted_elapsed", 0L)

        // Basic monotonic clock check: if the user rolled the wall clock backwards significantly
        // within the same boot, invalidate the tampered pass cleanly.
        val validExpires = if (expires > now && grantedAt > 0L && now < grantedAt - 600_000L) {
            0L
        } else if (expires > now && grantedElapsed > 0L && SystemClock.elapsedRealtime() >= grantedElapsed) {
            val elapsedSinceGrant = SystemClock.elapsedRealtime() - grantedElapsed
            if (elapsedSinceGrant > PASS_DURATION_MS) 0L else expires
        } else if (expires > now) {
            expires
        } else {
            0L
        }

        var watched = p.getInt("ads_watched", 0).coerceIn(0, REQUIRED_ADS - 1)
        val cycleStarted = p.getLong("cycle_started_at", 0L)
        if (watched > 0 && (cycleStarted <= 0L || now - cycleStarted > PASS_DURATION_MS)) {
            watched = 0
            p.edit().putInt("ads_watched", 0).remove("cycle_started_at").apply()
        }

        _state.value = _state.value.copy(
            adsWatched = watched,
            passExpiresAtMillis = validExpires,
        )
    }

    private fun ensureSdk(context: Context, onReady: () -> Unit) {
        if (sdkInitialized) {
            onReady()
            return
        }
        runCatching {
            MobileAds.initialize(context.applicationContext) {
                sdkInitialized = true
                onReady()
            }
        }.onFailure {
            _state.value = _state.value.copy(
                loadingAd = false,
                error = "Não foi possível iniciar o serviço de anúncios agora.",
            )
        }
    }

    private fun buildNonPersonalizedRequest(): AdRequest {
        val extras = Bundle().apply { putString("npa", "1") }
        return AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            .build()
    }

    /** Loads and shows one opt-in Rewarded Ad; credits progress only when AdMob confirms completion. */
    fun watchNextAd(activity: Activity, onPassUnlocked: () -> Unit = {}) {
        reloadState()
        if (_state.value.loadingAd || _state.value.showingAd) return
        _state.value = _state.value.copy(loadingAd = true, error = null, statusMessage = null)

        val cached = preloadedAd
        if (cached != null) {
            preloadedAd = null
            showLoadedAd(activity, cached, onPassUnlocked)
            return
        }

        ensureSdk(activity) {
            val unitId = BuildConfig.ADMOB_REWARDED_UNIT_ID
            RewardedAd.load(
                activity,
                unitId,
                buildNonPersonalizedRequest(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        if (activity.isFinishing || activity.isDestroyed) {
                            preloadedAd = ad
                            _state.value = _state.value.copy(loadingAd = false)
                            return
                        }
                        showLoadedAd(activity, ad, onPassUnlocked)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        _state.value = _state.value.copy(
                            loadingAd = false,
                            error = "Nenhum anúncio disponível agora (${error.code}). Confira a internet e tente de novo em instantes.",
                        )
                    }
                },
            )
        }
    }

    private fun showLoadedAd(activity: Activity, ad: RewardedAd, onPassUnlocked: () -> Unit) {
        var rewardEarned = false
        _state.value = _state.value.copy(loadingAd = false, showingAd = true, error = null)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                _state.value = _state.value.copy(
                    showingAd = false,
                    statusMessage = if (!rewardEarned) "Assista o anúncio até o final para contar no seu passe de 24h." else _state.value.statusMessage,
                )
                // Preload the second ad quietly if the user just finished ad #1.
                if (rewardEarned && _state.value.adsWatched == 1 && !_state.value.isAdPassActive()) {
                    preloadQuietly()
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                _state.value = _state.value.copy(
                    showingAd = false,
                    error = "Não deu para exibir o anúncio agora. Tente novamente.",
                )
            }
        }

        ad.show(activity) { _ ->
            rewardEarned = true
            creditOneAd(onPassUnlocked)
        }
    }

    private fun creditOneAd(onPassUnlocked: () -> Unit) {
        val now = System.currentTimeMillis()
        val p = prefs
        val nextCount = (_state.value.adsWatched + 1).coerceAtMost(REQUIRED_ADS)
        if (nextCount >= REQUIRED_ADS) {
            val expires = now + PASS_DURATION_MS
            p?.edit()
                ?.putInt("ads_watched", 0)
                ?.remove("cycle_started_at")
                ?.putLong("pass_expires_at", expires)
                ?.putLong("pass_granted_at", now)
                ?.putLong("pass_granted_elapsed", SystemClock.elapsedRealtime())
                ?.apply()
            _state.value = _state.value.copy(
                adsWatched = 0,
                passExpiresAtMillis = expires,
                statusMessage = "Premium liberado por 24 horas!",
                error = null,
            )
            onPassUnlocked()
        } else {
            p?.edit()
                ?.putInt("ads_watched", nextCount)
                ?.putLong("cycle_started_at", now)
                ?.apply()
            _state.value = _state.value.copy(
                adsWatched = nextCount,
                statusMessage = "$nextCount de $REQUIRED_ADS anúncios assistidos — falta só mais 1 para liberar 24h de Premium!",
                error = null,
            )
        }
    }

    private fun preloadQuietly() {
        val ctx = appContext ?: return
        if (preloadedAd != null || !sdkInitialized) return
        runCatching {
            RewardedAd.load(
                ctx,
                BuildConfig.ADMOB_REWARDED_UNIT_ID,
                buildNonPersonalizedRequest(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) { preloadedAd = ad }
                    override fun onAdFailedToLoad(error: LoadAdError) {}
                },
            )
        }
    }
}

package com.apprefer.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apprefer.sdk.AppRefer
import com.apprefer.sdk.billing.AppReferBilling
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val deviceIdView = findViewById<TextView>(R.id.device_id)
        val statusView = findViewById<TextView>(R.id.status)
        val trackButton = findViewById<Button>(R.id.btn_track_event)
        val advancedMatchingButton = findViewById<Button>(R.id.btn_set_advanced_matching)
        val setUserIdButton = findViewById<Button>(R.id.btn_set_user_id)
        val fuzzButton = findViewById<Button>(R.id.btn_fuzz)
        val billingButton = findViewById<Button>(R.id.btn_billing)

        statusView.text = "Configuring AppRefer…"

        // Normally in Application.onCreate. Uses the AppRefer.configure SDK key.
        AppReferBilling.configure(
            context = this,
            entitlementsByProduct = mapOf("pro_monthly" to listOf("premium")),
            debug = true,
            logLevel = 3,
        )

        lifecycleScope.launch {
            val attribution = AppRefer.configure(
                context = this@MainActivity,
                apiKey = intent.getStringExtra("api_key") ?: BuildConfigSample.API_KEY,
                debug = true,
                logLevel = 3,
            )

            deviceIdView.text = "device_id: ${AppRefer.getDeviceId() ?: "—"}"

            // QA hook: `adb shell am start -n com.apprefer.sample/.MainActivity --ez open_support true --es api_key pk_test_…`
            // Optional: `--es compose "text"` (home with the field pre-filled), `--es accent "#FFD60A"`
            // (bright accents switch hero text to dark), `--es team_names "Jay,Ana"` ("" for none).
            if (intent.getBooleanExtra("open_support", false)) {
                AppRefer.setUserId("support-sample-demo")
                AppRefer.support.identify(name = "María López")
                AppRefer.support.teamNames = (intent.getStringExtra("team_names") ?: "Jay,Ana")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                intent.getStringExtra("accent")?.let { runCatching { AppRefer.support.accentColor = android.graphics.Color.parseColor(it) } }
                val draft = intent.getStringExtra("compose")
                if (draft != null) AppRefer.support.compose(this@MainActivity, draft) else AppRefer.support.open(this@MainActivity)
            }

            statusView.text = if (attribution == null) {
                "Attribution: (none — organic or network failure)"
            } else {
                buildString {
                    appendLine("network: ${attribution.network}")
                    appendLine("match_type: ${attribution.matchType}")
                    appendLine("campaign: ${attribution.campaignName ?: "—"}")
                    appendLine("campaign_id: ${attribution.campaignId ?: "—"}")
                    appendLine("ad_id: ${attribution.adId ?: "—"}")
                    appendLine("fbclid: ${attribution.fbclid ?: "—"}")
                    appendLine("gclid: ${attribution.gclid ?: "—"}")
                    appendLine("ttclid: ${attribution.ttclid ?: "—"}")
                    append("created: ${attribution.createdAt}")
                }
            }
        }

        trackButton.setOnClickListener {
            lifecycleScope.launch {
                AppRefer.trackEvent(
                    eventName = "test_event",
                    properties = mapOf("source" to "sample_app"),
                )
                Toast.makeText(
                    this@MainActivity,
                    "trackEvent sent: test_event",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        advancedMatchingButton.setOnClickListener {
            lifecycleScope.launch {
                AppRefer.setAdvancedMatching(
                    email = "joe@example.com",
                    phone = "+1 (555) 123-4567",
                    firstName = "Joe",
                    lastName = "Doe",
                    dateOfBirth = "19900115",
                )
                Toast.makeText(
                    this@MainActivity,
                    "setAdvancedMatching sent (hashed)",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        setUserIdButton.setOnClickListener {
            lifecycleScope.launch {
                AppRefer.setUserId("sample_user_${System.currentTimeMillis()}")
                Toast.makeText(
                    this@MainActivity,
                    "setUserId sent",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        billingButton.setOnClickListener {
            lifecycleScope.launch {
                val info = AppReferBilling.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
                val premium = info.entitlements["premium"]?.isActive == true
                Toast.makeText(
                    this@MainActivity,
                    "${info.appUserId}: premium=$premium (${info.source})",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // Fuzz test: extreme inputs should NEVER crash the host app. Demonstrates
        // `SafeRunner.safely` guarantees — a 100 KB event name and a 1 MB byte
        // array inside properties would corrupt JSON serialization without the
        // crash guard, but the SDK should swallow the error and keep running.
        fuzzButton.setOnClickListener {
            lifecycleScope.launch {
                AppRefer.trackEvent(
                    eventName = "x".repeat(100_000),
                    properties = mapOf(
                        "big_bytes" to ByteArray(1_000_000),
                        "null_value" to null,
                        "nested" to mapOf("a" to mapOf("b" to mapOf("c" to "d"))),
                        "unicode" to "🚀💻🔥",
                    ),
                    revenue = Double.MAX_VALUE,
                    currency = "",
                )
                Toast.makeText(
                    this@MainActivity,
                    "Fuzz test completed — no crash",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/** Keep the sample API key out of source tracking — replace with a real `pk_test_...`. */
private object BuildConfigSample {
    const val API_KEY: String = "pk_test_demo"
}

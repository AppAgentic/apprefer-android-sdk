package com.apprefer.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apprefer.sdk.AppRefer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val deviceIdView = findViewById<TextView>(R.id.device_id)
        val statusView = findViewById<TextView>(R.id.status)
        val trackButton = findViewById<Button>(R.id.btn_track_event)

        statusView.text = "Configuring AppRefer…"

        lifecycleScope.launch {
            val attribution = AppRefer.configure(
                context = this@MainActivity,
                apiKey = BuildConfigSample.API_KEY,
                debug = true,
                logLevel = 3,
            )

            deviceIdView.text = "device_id: ${AppRefer.getDeviceId() ?: "—"}"

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
            // Phase 4 will wire this up — for now it logs and shows a toast.
            lifecycleScope.launch {
                AppRefer.trackEvent(
                    eventName = "test_event",
                    properties = mapOf("source" to "sample_app"),
                )
                Toast.makeText(
                    this@MainActivity,
                    "trackEvent fired (stub — phase 4 wiring pending)",
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

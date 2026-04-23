package com.apprefer.sample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apprefer.sdk.AppRefer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tv = findViewById<TextView>(R.id.status)
        tv.text = "Configuring AppRefer…"
        lifecycleScope.launch {
            val attribution = AppRefer.configure(this@MainActivity, apiKey = "pk_test_demo")
            tv.text = "Attribution: ${attribution?.network ?: "null"} (${attribution?.matchType})"
        }
    }
}

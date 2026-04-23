# AppRefer Android SDK

Native Android SDK for [AppRefer](https://apprefer.com) — mobile attribution
for Play Store apps. Version-locked with the iOS, Flutter, and React Native
SDKs.

> **Status:** 0.4.1 — full attribution flow, event tracking, and Meta
> Advanced Matching wired. Maven Central publish config ready; artifact
> will be live as soon as the `com.apprefer` Sonatype namespace is claimed.

## Install

```kotlin
dependencies {
    implementation("com.apprefer:apprefer-android-sdk:0.4.1")
}
```

## Usage

```kotlin
import com.apprefer.sdk.AppRefer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lifecycleScope.launch {
            val attribution = AppRefer.configure(
                context = this@MainActivity,
                apiKey = "pk_live_...",
            )
            // attribution.network, attribution.campaign, attribution.matchType

            // Track custom events (non-purchase):
            AppRefer.trackEvent("tutorial_complete", properties = mapOf("step" to 3))

            // Meta Advanced Matching — hashed on-device before sending.
            AppRefer.setAdvancedMatching(
                email = "user@example.com",
                phone = "+15551234567",
            )

            // Link this device to your user / RevenueCat ID.
            AppRefer.setUserId("rc_abc123")
        }
    }
}
```

Java-friendly callback variant:

```java
AppRefer.configure(
    this, "pk_live_...", /* userId */ null, /* debug */ false, /* logLevel */ 1,
    new AppReferCallback<Attribution>() {
        @Override public void onResult(Attribution result) { /* ... */ }
        @Override public void onError(Throwable error) { /* ... */ }
    }
);
```

## Permissions

The SDK declares only `android.permission.INTERNET`. It does **not** request
`com.google.android.gms.permission.AD_ID` — you can leave your Play Console
Data Safety form accordingly.

## Requirements

- `minSdk` 21 (Android 5.0)
- `compileSdk` 35
- JDK 17
- Kotlin 2.x

## Docs

Full integration and dashboard docs: https://apprefer.com/docs

## License

MIT — see [LICENSE](LICENSE).

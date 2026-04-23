# AppRefer Android SDK

Native Android SDK for [AppRefer](https://apprefer.com) — mobile attribution
for Play Store apps. Version-locked with the iOS, Flutter, and React Native
SDKs.

> **Status:** 0.4.1 — Phase 1 skeleton. Public API is stable; the attribution
> flow ships in 0.4.2.

## Install

```kotlin
dependencies {
    implementation("com.apprefer:apprefer-android-sdk:0.4.1")
    // pending Maven Central publish — for now, build from source
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
        }
    }
}
```

Java-friendly callback variant:

```java
AppRefer.INSTANCE.configure(
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

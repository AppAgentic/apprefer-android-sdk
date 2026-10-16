# AppRefer Android SDK

## 0.9.1: account switching, independent Support and reliable events

Support can start independently of attribution. Its configuration does not configure
attribution, collect attribution device signals, initialize product analytics, or
create a device id. Full configuration still enables attribution and analytics as
before. Opening Support before either configuration reports the existing
not-configured error.

Await reset() before signing the next account in. Reset cancels outstanding
attribution, event and Support work, best-effort revokes the old Support session
and unregisters its push token, clears the session/profile and displayed
conversations/unread/drafts, removes the user id and transient matching data,
and drops queued product events. It is idempotent and safe before configuration.
The key remains configured so the next account can use Support; install device id,
attribution result, consent and conversion state are retained. Advanced-matching
hashes are never persisted by this SDK. Billing is separate: also call
AppReferBilling.logOut() when your app uses Billing, then identify its next user
through Billing's existing login API.
After reset, attribution sends nothing (no consent re-report, user id, advanced
matching or event delivery) and Support sends no device id until configure is
called again, so the next account's Support session is never linked to the
previous account's install. Support also omits the device id while collection is off.

```kotlin
AppRefer.support.configure(context, apiKey = "pk_...") // Support only
AppRefer.setUserId("account-A") // suspending
AppRefer.support.identify(name = "Alex", email = "alex@example.com")
AppRefer.reset()
AppRefer.setUserId("account-B")

when (val result = AppRefer.configureResult(context, apiKey = "pk_...")) {
    is AppReferAttributionResult.Attributed -> println(result.attribution.network)
    AppReferAttributionResult.Pending -> Unit
    AppReferAttributionResult.Organic -> Unit
    is AppReferAttributionResult.Failed -> println(result.error)
}
val delivery = AppRefer.trackEventResult("workout_finished", properties = mapOf("sets" to 4))
AppRefer.clearPendingEvents()
// If Billing is configured, also: AppReferBilling.logOut()
```

The existing configure return value and trackEvent API remain available.
The new configureResult reports attributed, organic, pending, or failed; organic requires
a successful server answer (or its confirmed install cache), never a network
failure or an unclassified empty response. Pending means the server is holding
the ATT decision; its cached copy remains pending until the server confirms a result.
A failed consent re-report preserves any previously confirmed attribution.

trackEventResult returns sent, queued or dropped (uppercase enum cases on
Android). Sent means HTTP 2xx acknowledgement; queued means saved for later
delivery; dropped covers a disabled/unconfigured SDK, a terminal rejection or
unpersistable input. Product events are persisted before sending, bounded to
1,000 events and seven days; expiry and overflow discard the oldest entries.
Each event keeps one stable eventId. Network failures, HTTP 408/429 and 5xx retry
with exponential backoff capped at one hour, or longer when Retry-After requires it.
A retryable failure pauses the drain. HTTP 2xx and other 4xx responses are terminal.
Awaited event calls wait only for their own single request, never the full backlog.
This is **at-least-once delivery**: BigQuery's short insert-id deduplication window
does not provide an exactly-once guarantee.

clearPendingEvents() cancels only product-event requests and erases the queue; call
it when withdrawing product-event consent. Reset calls it too. The existing SDK
kill switch also clears queued events and stops sends. ATT/advertising consent
continues to select the appropriate host; it is not a new product-analytics
consent switch. Discarded events are never replayed. Await reset/clear before
starting work for the next account or resuming event collection.


Native Android SDK for [AppRefer](https://apprefer.com) — mobile attribution
for Play Store apps. Version-locked with the iOS, Flutter, and React Native
SDKs.

> **Status:** 0.9.1 — full attribution flow, event tracking, and Meta
> Advanced Matching wired. Distributed via JitPack today; Maven Central
> publish config is ready and will activate once the `com.apprefer`
> Sonatype namespace is claimed.

## Install

Add the JitPack repository (once per project):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependency:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.AppAgentic:apprefer-android-sdk:0.9.1")
}
```

From the first release that includes AppRefer Billing, the repo publishes two
modules, and the `com.github.AppAgentic:apprefer-android-sdk:<tag>` coordinate
above brings in **both** (Play Billing, the `BILLING` permission and `minSdk` 23).
Pick the module you need instead:

```kotlin
dependencies {
    // Attribution only (minSdk 21, no Play Billing)
    implementation("com.github.AppAgentic.apprefer-android-sdk:apprefer-android-sdk:<tag>")
    // Attribution + subscriptions (AppRefer Billing, minSdk 23)
    implementation("com.github.AppAgentic.apprefer-android-sdk:apprefer-billing:<tag>")
}
```

Every tag on this repo is published automatically by JitPack — no accounts
or tokens required.

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

## Subscriptions (AppRefer Billing)

AppRefer Billing replaces RevenueCat for Google Play subscriptions and one-time products. Play Billing Library 8 handles the purchase, AppRefer verifies it with Google, acknowledges it, keeps the entitlements and sends the conversion to your ad networks from the same attribution data. It lives in the separate `apprefer-billing` module (`minSdk` 23, Kotlin 2.1+ because Play Billing 8.3 ships Kotlin 2.2 metadata); the attribution module does not depend on it.

Billing must be enabled for the app in AppRefer (`billing.mode` `shadow` or `primary`, with the package name, a Play service account and products configured).

### Set up

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch { AppRefer.configure(this@App, apiKey = "pk_live_...") }
        // Uses the same SDK key. Connects to Google Play and sends any unfinished purchases right away.
        AppReferBilling.configure(
            context = this,
            appUserId = null,  // null = anonymous "$ARAnon:<UUID>", kept on device
            entitlementsByProduct = mapOf(
                "pro_monthly" to listOf("premium"),
                "pro_yearly" to listOf("premium"),
            ),
            consumableProductIds = setOf("coins_100"),  // one-time products that can be bought again
        )
    }
}
```

`entitlementsByProduct` is only used when AppRefer can't be reached (see Outage safety). Unlisted products fall back to the mapping the server last returned, then `defaultEntitlement` (default `premium`, the same as the server). One-time products not in `consumableProductIds` are treated as non-consumable (lifetime).

### Products, purchase, restore

```kotlin
val products = AppReferBilling.products(listOf("pro_monthly", "pro_yearly"))  // Play ProductDetails, in this order

val result = AppReferBilling.purchase(activity, products[1])
when (result.outcome) {
    AppReferBilling.PurchaseResult.Outcome.PURCHASED -> unlock(result.customerInfo)
    AppReferBilling.PurchaseResult.Outcome.USER_CANCELLED -> Unit
    AppReferBilling.PurchaseResult.Outcome.PENDING -> showPendingNote()  // arrives later via customerInfoStream
}

val info = AppReferBilling.restorePurchases()  // queryPurchasesAsync + sends every active purchase
```

`purchase` sets `BillingFlowParams.setObfuscatedAccountId` to the customer's AppRefer account token and sends the purchase to AppRefer, which verifies and **acknowledges** it. Consumables are consumed by the SDK only after AppRefer has accepted them.

- Subscription offers: pass `offerToken` (from `product.subscriptionOfferDetails`). Without one the SDK picks a free-trial offer the user is eligible for, else the base plan (`AppReferBilling.defaultOfferToken(product)`). Products with several base plans should pass the token they display.
- Upgrades/downgrades: `purchase(activity, newProduct, oldProductId = "pro_monthly", replacementMode = SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION)`.
- Pending purchases (cash, bank transfer) are enabled. They return `PENDING`, grant nothing, and are sent to AppRefer once Google Play reports them paid (via `onPurchasesUpdated`, or the next launch/foreground), for the user who started them even after a restart, `logOut` or user switch: who started a purchase is saved before Google Play opens. If that can't be saved, `purchase` throws `AppReferBillingException.Storage` without opening Google Play.
- Errors from Google Play throw `AppReferBillingException.Store(responseCode, debugMessage)` (e.g. `ITEM_ALREADY_OWNED`: the owned purchase is sent to AppRefer first, so a `restorePurchases()` or `customerInfo()` call afterwards shows it).

### Entitlements

```kotlin
val info = AppReferBilling.customerInfo()                                   // cached for 5 minutes, then fetched
val fresh = AppReferBilling.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
if (info.entitlements["premium"]?.isActive == true) { /* unlocked */ }

AppReferBilling.customerInfoStream.collect { update(it) }                   // Flow, latest value first
AppReferBilling.listener = AppReferBillingListener { update(it) }           // or a listener, on the main thread
```

`customerInfo` never throws. `info.source` tells you where the answer came from: `SERVER`, `CACHE` or `LOCAL_FALLBACK`.

### Users

```kotlin
AppReferBilling.logIn("user_123")    // moves purchases made while anonymous to this user
AppReferBilling.logOut()             // new anonymous id
AppReferBilling.setAttributes(mapOf("\$email" to "a@b.com", "\$displayName" to "Alex"))  // sent with the next call
AppReferBilling.setEmail("a@b.com")  // also setDisplayName / setPhoneNumber
```

The AppRefer attribution device id is sent automatically as `appreferId`, so purchases are matched to the ad click without `setUserId`.

Java: every suspend call has a callback overload taking `AppReferCallback<T>` (results on the main thread), e.g. `AppReferBilling.customerInfo(callback)`, `purchase(activity, product, null, callback)`, `restorePurchases(callback)`, `logIn(id, callback)`.

### Outage safety

A paying user is never locked out because AppRefer is down:

- If AppRefer can't be reached or returns an error, entitlements come from Google Play's own active purchases (`queryPurchasesAsync`: purchased, not pending, not consumable), mapped through `entitlementsByProduct`, merged with the last server answer for the same user. During an outage every purchase on the device counts.
- When AppRefer does answer, the device's purchases that belong to this user are still merged in, so a purchase the server never got (upload rejected or given up, no account token) never locks a payer out. A purchase belongs to the user when it has no `obfuscatedAccountId`, carries this user's account token, or is still queued for them; one tagged with another user's token is left out.
- A purchase AppRefer can't accept right now is saved to a retry queue on disk (max 50 attempts / 90 days). It is resent on the next launch, on foreground and after the next successful call, always for the user who made it. A 401/403/404/409/429 pauses the queue (30 s doubling to 1 h, or the server's `Retry-After`) without using up attempts; 400/413/422 are final. Google's real-time developer notifications also reach AppRefer on their own.
- Google refunds purchases that aren't acknowledged within 3 days. AppRefer acknowledges when it records a purchase; if the upload fails, the SDK acknowledges on device as soon as the retry copy is on disk (never consumes: consumables are consumed only once AppRefer has recorded them). Failed acknowledgements and consumes are retried at launch and on foreground.
- A purchase AppRefer refuses for good (400/413/422), that has no usable store data, or that the retry queue gives up on is saved to a rejected list on disk before it is acknowledged (it is never consumed; until the copy is saved it stays unacknowledged). `AppReferBilling.rejectedPurchases()` lists them for support, and `restorePurchases()` sends them again.
- The rejected list has no size limit: an entry leaves it only once AppRefer records that purchase.
- Who opened each purchase sheet is saved first (the sheet doesn't open if that fails), so a purchase delivered later without an account token goes to whoever started the oldest intent for that product (earliest start time). Renewals (order ids ending `..N`) never match or use up an intent on any path; a renewal returned to the open purchase sheet settles it without its intent. Intents leave only on a definitive outcome (recorded, durably queued, archived, or a store result of USER_CANCELED, ITEM_UNAVAILABLE, DEVELOPER_ERROR, FEATURE_NOT_SUPPORTED, BILLING_UNAVAILABLE or ITEM_ALREADY_OWNED), never by age or on ambiguous errors.
- If the saved intents are malformed, entries that still decode are kept, the raw original is set aside (`purchase_intents.*.corrupt`) and an intents-lost marker is saved. Untagged purchases then stay open for up to 48 hours from when they were made or when the intents were lost, whichever is earlier (inside Google's 3-day acknowledgement window, retried on foreground), then go to the current user with a support note (logged, and on the rejected entry if AppRefer refuses it). The marker clears once nothing is waiting on it and it is 48 hours old.
- Only `restorePurchases()` may move a purchase that belongs to another app user to the current one (it sends `"restore": true`); purchases and Play's purchase listener never do.
- `logOut()` keeps any anonymous-to-user merge that hasn't reached AppRefer yet; waiting merges are sent in order.
- If the billing identity can't be read from storage, the SDK answers from Google Play alone and never mints a replacement id.
- The Play connection uses `enableAutoServiceReconnection()` plus a retried first connection.

### Migrating from RevenueCat

**No RevenueCat data export is needed.** On first billing configure for each install and user, AppRefer imports the store's active purchases in the background, including already-acknowledged Google Play purchases. Logging in as a new user runs the import for that user. Each purchase is sent without `restore: true`, so it fills unowned purchases and records state without transferring purchases already owned by another user. The completion flag is saved only after every purchase is accepted or durably queued for retry. Migration never finishes, acknowledges or consumes a purchase on device; retry uploads use the same rule. Purchases older than 24 hours update state without an ad conversion. Keep the same app user IDs and configure store notifications for renewals.

| RevenueCat | AppRefer Billing |
|---|---|
| `Purchases.configure(PurchasesConfiguration.Builder(ctx, key).appUserID(id).build())` | `AppReferBilling.configure(ctx, appUserId = id, entitlementsByProduct = ...)` (after `AppRefer.configure(ctx, apiKey)`) |
| `Purchases.sharedInstance.awaitOfferings()` / `StoreProduct` | `AppReferBilling.products(listOf("id", ...))` → Play `ProductDetails` |
| `awaitPurchase(PurchaseParams.Builder(activity, product).build())` → `PurchaseResult(storeTransaction, customerInfo)` | `AppReferBilling.purchase(activity, product)` → `PurchaseResult` (`.purchase`, `.customerInfo`, `.userCancelled`, `.outcome`) |
| `PurchaseParams.Builder(...).oldProductId(...).googleReplacementMode(...)` | `purchase(activity, product, oldProductId = ..., replacementMode = ...)` |
| `PurchasesTransactionException` with `userCancelled` | `outcome == USER_CANCELLED` (not an exception) |
| `Purchases.sharedInstance.awaitRestore()` | `AppReferBilling.restorePurchases()` |
| `awaitCustomerInfo(CacheFetchPolicy.CACHED_OR_FETCHED / FETCH_CURRENT)` | `AppReferBilling.customerInfo(FetchPolicy.CACHED_OR_FETCHED / FETCH_CURRENT)` |
| `customerInfo.entitlements["premium"]?.isActive` | same |
| `customerInfo.entitlements.active` / `.activeSubscriptions` | same |
| `EntitlementInfo.expirationDate / willRenew / periodType / productIdentifier / store` | same names |
| `updatedCustomerInfoListener` | `AppReferBilling.listener` / `customerInfoStream` |
| `awaitLogIn(id)` / `awaitLogOut()` | `AppReferBilling.logIn(id)` / `logOut()` |
| `Purchases.sharedInstance.appUserID` / `isAnonymous` | `AppReferBilling.appUserId` / `isAnonymous` |
| `$RCAnonymousID:<hex>` | `$ARAnon:<UUID>` |
| `setEmail` / `setDisplayName` / `setPhoneNumber` / `setAttributes` | same names on `AppReferBilling` |
| RevenueCat consumes all one-time purchases by default | list consumables in `consumableProductIds`; others are lifetime |
| `AppRefer.setUserId(rcUserId)` | not needed: the device id is linked automatically |

If a file imports both SDKs during the switch, use `AppReferCustomerInfo` / `AppReferEntitlementInfo` (the `CustomerInfo` / `EntitlementInfo` typealiases clash with RevenueCat's names).

Testing: use a license tester account on an internal testing track; AppRefer records those purchases as sandbox. AppRefer rejects real (production) purchases sent with a test SDK key. Play Billing needs a device or emulator image with the Play Store.

## Permissions

The SDK declares only `android.permission.INTERNET`. It does **not** request
`com.google.android.gms.permission.AD_ID` — you can leave your Play Console
Data Safety form accordingly.

## Requirements

- `minSdk` 21 (Android 5.0); 23 with `apprefer-billing`
- `compileSdk` 35
- JDK 17
- Kotlin 2.x (2.1+ with `apprefer-billing`)

## Docs

Full integration and dashboard docs: https://apprefer.com/docs

## License

MIT — see [LICENSE](LICENSE).

## Support chat (AppRefer Support)

Native Android help chat answered from the AppRefer inbox. No additional runtime dependencies.
Configure AppRefer first, then call from a Help button:

```kotlin
AppRefer.support.open()                      // optional conversationId
AppRefer.support.identify("María", "maria@example.com")
AppRefer.support.accentColor = Color.rgb(120, 82, 255)
AppRefer.support.title = "My app support"     // defaults to application label + " support"
AppRefer.support.replyTime = "under an hour"  // completes "We usually reply in …"
AppRefer.support.teamNames = listOf("Jay")    // first names shown on the help home
AppRefer.support.appIcon = drawable           // defaults to the launcher icon, else the app's initials
AppRefer.support.compose("Problem with my subscription") // opens the help home, field pre-filled and focused

// Quick-help tiles (defaults: Billing & refunds, Restore purchase, Report a bug, Suggest an idea).
// A tile starts a message with its title as the subject, or runs `action` instead. Empty hides them.
AppRefer.support.topics = listOf(
    AppReferSupportTopic.billing,
    AppReferSupportTopic.restore.copy(action = { restorePurchases() }),
    AppReferSupportTopic("Report a bug", "Screenshots welcome", R.drawable.ic_bug, Color.RED, "What went wrong?"),
)

// From a coroutine, after obtaining an FCM token:
AppRefer.support.registerPushToken(token)
val unread = AppRefer.support.unreadCount()
AppRefer.support.removePushToken(token)       // when unregistering this device

// From your notification click handler (not on receipt of a background message):
if (AppRefer.support.handleNotification(data)) return
```

The host owns Firebase Messaging integration and notification permission prompts. Support creates
channel `apprefer_support`; post support reply notifications to that channel and pass FCM data
`{type: "support_reply", conversationId: "…"}` to the click handler. No Firebase dependency is added.
An explicit `Context` overload is available for `open(context, conversationId)` and `compose(context, prefill)`.

The help home has a coloured hero built from `accentColor` (its text turns dark on a bright accent
such as yellow), a "Continue" card for the open conversation (unread first, then the newest), the
quick-help tiles, earlier conversations and a message field pinned to the bottom. Messages start
right from that field; there is no separate compose screen. Conversations show who is answering
(team members appear by first name only), group bubbles, and offer quick replies after an answer.

Embed `com.apprefer.sdk.support.AppReferSupportView(context)` in your own layout instead of opening
the Activity. Wire `onClose`, `onPickImages` (system photo picker, maximum remaining count) and pass
returned URIs to `addImages`. Forward host `onResume`/`onPause` to `resume`/`pause`; detachment also
stops polling. The full-screen Activity wires these automatically.

Customers can send up to four screenshots, including image-only messages. Images shrink to at
most 1600 px and 700 KB before direct storage uploads. Home refreshes every 15 seconds; visible
conversations check every 4 seconds and merge replies by ID. Failed messages remain available to
retry. Network failures leave a new-message draft in place. Tokens use Android Keystore encryption
and no-backup files. Dark mode follows the device; text follows system font scaling.

## Tracking consent (0.6.0)

The SDK never prompts for ATT. On iOS, it reads the current ATT status:
authorized → `granted`, denied/restricted → `denied`, and notDetermined →
`undetermined` (before iOS 14: `granted`). Call
`trackingConsentDidChange()` immediately after your app's own ATT prompt.
Configure and app activation also check for a changed decision, independently
of the first-open attribution flag. A failed report retries on the next trigger;
a successful report updates the cached attribution and saved reported consent.

On Android, call `setTrackingConsent(true)` or `setTrackingConsent(false)`
with your app's choice. The choice persists across launches. Until set (or after
passing `null`), requests omit `tracking_consent` and retain legacy routing.

Call the setter before or after configure. A choice made before the first
configure is staged in memory and persisted when configure supplies a Context.

```kotlin
AppRefer.setTrackingConsent(false) // denied; use true for granted, null for legacy
AppRefer.trackingConsentDidChange()
```

Every attribution, event, user-linking and billing request selects its host from
the current consent: granted uses only `https://trk.apprefer.com`;
denied/undetermined use only `https://apprefer.com`, with no cross-host
fallback. Android legacy requests retain tracking-host primary and main-host
fallback. Support always uses `https://apprefer.com`. IDFA is sent only when
granted; GAID is sent only when granted or legacy. Advanced matching is dropped
locally unless tracking is granted or legacy. First-party analytics, billing and
support continue to work when tracking is denied.

For iOS apps (including Flutter and React Native), declare **only**
`trk.apprefer.com` in your app's `PrivacyInfo.xcprivacy` tracking-domain list:

```xml
<key>NSPrivacyTrackingDomains</key>
<array>
    <string>trk.apprefer.com</string>
</array>
```

Do not add `apprefer.com` to `NSPrivacyTrackingDomains`; it serves the
first-party path when ATT has not been granted.

## SKAdNetwork and AdAttributionKit (0.7.0+)

Apple's own ad attribution works for every iOS install, including users who don't allow tracking: the phone ties the install to the ad and Apple sends the ad network an anonymous postback with your app's conversion value. Only the app can set that value, and Apple sends no postback until the app sets it at least once.

AppRefer does this for you:

- On first launch it registers the install (value 0, coarse `low`), as Apple recommends.
- It then sets the value AppRefer computes from what it already knows: **10** (`medium`) once a free trial starts, **20 + whole dollars paid** (`high`, capped at 63) once the user pays, e.g. $9.99 → 30, $39.99 → 60. Values only go up and windows are never locked early.
- It checks on launch, when the app becomes active, after trial or revenue events and after AppReferBilling purchases, for the 35 days Apple's postbacks cover. If you take payments elsewhere (e.g. RevenueCat), call `await AppRefer.updateConversionValue()` after a purchase to set it sooner.
- It updates both AdAttributionKit (iOS 17.4+) and SKAdNetwork (iOS 15.4+), as Apple asks when your ad networks may use either.

To receive copies of the winning postbacks in AppRefer, add to your app's Info.plist:

```xml
<key>NSAdvertisingAttributionReportEndpoint</key>
<string>https://apprefer.com</string>
<key>AdAttributionKit</key>
<dict>
    <key>AttributionCopyEndpoint</key>
    <string>https://apprefer.com</string>
</dict>
```

Also add the `SKAdNetworkItems` identifiers your ad networks publish.

On Android, `AppRefer.updateConversionValue()` is a public no-op for API parity. The conversion-value feature above is iOS-only; no Apple conversion requests or postbacks run on Android. Add the Info.plist keys above to your iOS app only.

## Offerings (0.8.0)

Configure named offerings, packages, placements, targeting and experiments in AppRefer. Your paywall reads the store products without hard-coding product ids.

```kotlin
val offerings = AppReferBilling.offerings()
val annual = offerings.currentOffering("onboarding")?.annual
if (annual != null) {
    // Show annual.storeProduct's localized price in your paywall.
    val result = AppReferBilling.purchase(activity, annual)
}
```

`current` is the resolved default, and `all` contains offerings by identifier. Each offering has JSON `metadata`, `availablePackages`, a package lookup, and `weekly`, `monthly`, `twoMonth`, `threeMonth`, `sixMonth`, `annual` and `lifetime` shortcuts. `$rc_*` package identifiers are accepted alongside `$ar_*`; unrecognized types become `custom`. Packages missing a product for this platform or unavailable in the store are omitted.

Unknown placements fall back to current; a configured null placement suppresses the paywall. Placement lookups return a copy carrying placement attribution. Purchase the package to send its offering/package, placement, targeting and experiment context, plus the persistent install bucket. Context survives pending purchases and retry-queue restarts.

Offerings use a five-minute memory and disk cache. Foreground refreshes stale data; login/logout invalidates it and refreshes an already-used catalogue. Network/server failures return the last cached response of any age after resolving store products again. No cache means the SDK billing error is thrown; catch it and use your existing paywall product ids. Offerings never gate configuration or purchases. Concurrent callers share one request.

Google Play `subscription:basePlan` selects that base plan; a bare subscription selects the period matching the package type, otherwise the first base plan. The package exposes the selected offer token. Storefront country uses ISO alpha-2 when available; no device locale is substituted. The install bucket stays stable after login/logout.

### Collection control (0.9.1)

```kotlin
AppRefer.setCollectionEnabled(false, context)
```

Call this when collection consent is withdrawn. The choice persists across launches. Disabling cancels in-flight attribution, conversion-value, consent and product-event traffic, clears the durable event queue, and blocks activation/background retries. Configure returns cached attribution (or an unavailable/failed result if none exists) while disabled. Support and Billing continue independently. Call `setCollectionEnabled(true)` and then configure again to resume; enabling alone sends nothing. Reset does not change this choice.

Product events include `app_user_id` from the identity set via `setUserId` when tracked. The identity is saved in the queued payload, so retries and relaunches retain the original account even after a user switch. Without an identity the field is omitted. Internal events whose names start with `_` retain their existing payloads.

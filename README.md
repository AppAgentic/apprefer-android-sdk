# AppRefer Android SDK

## 0.11.0 (unreleased): lifecycle notifications (engagement)

**Not generally available.** AppRefer turns lifecycle engagement on per app; until then every engagement call answers "disabled" quietly and nothing is stored. The server side is dry-run only in this release: no notification is sent to anyone yet. This version is the client contract (wire schema 1) the live sender will use.

Rules the SDK follows: nothing is registered until your app calls `start`; nothing asks for notification permission or tracking consent; the install secret lives only in secure storage (iOS Keychain / Android Keystore); an account is bound only with an assertion your backend gets from AppRefer (`POST /api/v1/apps/{appId}/engagement/assertions`, `engagement:identity` key), never a plain user id; Support sessions, tokens and `support_reply` pushes never enroll anyone, and Support's handlers ignore engagement pushes; a notification for another install or account, an expired one, or one that fails validation never navigates; each outcome (received, opened, button, snoozed, dismissed) is delivered once, and navigation waits for `setNavigationReady(true)`; a snooze is never a completion; receipts carry the message's own proof and are dropped after any account change; an account change also removes AppRefer's own snoozes and posted notifications for the previous account (on iOS a remote notification already on screen can't be withdrawn, but it never navigates or reports for the next account); queued events and identity changes carry the account epoch they were made under, so the server refuses one made for an account the install has left; destinations are in-app paths your app maps to screens, never URLs the SDK opens.

```kotlin
AppRefer.engagement.start(context, options = AppReferEngagementOptions(
    trackedEvents = setOf("setup_started", "setup_completed"),
    allowedImageHosts = setOf("assets.example.com"),
    smallIcon = R.drawable.ic_stat_notify))                  // monochrome; default: app icon

// Your FirebaseMessagingService (AppRefer adds no Firebase dependency):
override fun onMessageReceived(message: RemoteMessage) {
    if (AppRefer.engagement.handleMessage(this, message.data)) return
    if (AppRefer.support.handleNotification(message.data)) return
    // your own messages
}
override fun onNewToken(token: String) { scope.launch { AppRefer.engagement.setPushToken(token) } }

// Launch activity: onCreate and onNewIntent
AppRefer.engagement.handleIntent(this, intent)
AppRefer.engagement.onNotificationEvent = { e -> if (e.isNavigation) router.open(e.destination) }
AppRefer.engagement.setNavigationReady(true)

// After sign-in, with an assertion from your backend:
AppRefer.engagement.identify(assertion)
```

Declare `android.permission.POST_NOTIFICATIONS` and ask for it yourself on Android 13+; then call `refreshDeviceState()`. AppRefer sends data-only FCM messages and renders them itself (channels "Offers and news", "Reminders", "Account and billing" in the app's language). FCM limits apply honestly: delivery to a force-stopped app doesn't happen, normal-priority data messages can wait for Doze windows, and `onMessageReceived` has about 20 s (the image download is capped at 8 s, `imageTimeoutMs`). Taps open your launch activity; only intents carrying a nonce the SDK recorded are accepted.

## 0.10.0 (unreleased): help centre in the chat; the help chat speaks 35 languages

**Help centre.** When your app has published help articles in AppRefer, the help home
shows a "Help articles" card (search plus the top articles), with a help centre screen
(every article by category, search) and an article reader. "Still need help? Message
us" starts a conversation about the article. Nothing changes for apps without
articles. Read them in code too: `AppRefer.support.articles(query)`, `article(slug)`,
and `AppRefer.support.status()` to decide whether to register push tokens. See
[Help centre and status](#help-centre-and-status-0100). Also fixes a crash risk on
Android 5–6: the SDK no longer uses API 24+ collection methods.

**AppRefer Support is localized.** Every label, placeholder, error and accessibility
label in the help chat ships in 35 languages and follows the language your app is
running in, so the chat matches your screens. Override any string or force a language;
see [Support chat → Languages](#languages-and-your-own-wording). Arabic and Hebrew lay
out right to left.

## 0.9.3: the Billing account gate survives a relaunch

After `reset()` without `AppReferBilling.logOut()`, the Billing user that was current
at the reset gets no device id even after the app restarts: `reset()` saves it, and
until you call `AppReferBilling.logIn(...)` (even with the same id) or `logOut()`, or
the Billing user id changes, Billing sends that user no `appreferId` or device
attributes, never re-links it, and `/identify` sends `link: false`. A push token
re-registered while an old Support session still holds it moves to the current
session once the old one is revoked, instead of being posted at once.

## 0.9.2: Billing follows the collection choice; Support clearIdentity

**Billing sends no device id while it must not.** AppReferBilling uses the same rule
Support has used since 0.9.1: the install device id (`appreferId`) and the device
signals among the customer attributes (`$gpsAdId`, `$fbAnonId`, `$deviceVersion`,
`$ip`) are sent with customers/purchases requests only after
`configure`/`configureResult` in this process, never while collection is off
(including an "off" that could not be saved, or one turned back on but not yet
configured), and never after `reset()` until configure runs again for the next
user. A withheld device attribute is dropped, not sent later. While the gate is
closed Billing also uses `apprefer.com`, never `trk.apprefer.com`. Purchases,
restore, entitlements and offerings work exactly as before without it. Purchases
queued earlier keep their device id only if linking is allowed when they are
resent. A customer registered without the device id is linked once on the next
customer refresh after configure. Billing-only apps never send it.

**The device id follows the account.** After `reset()`, Billing sends the device id
only for the Billing user set after that reset (across relaunches since 0.9.3): call `AppReferBilling.logIn(...)`
(or `logOut()`) for the next account. Until then the earlier user, and any queued
purchase or renewal that belongs to someone other than the current Billing user,
gets no device id and is never re-linked, even once configure has run again.
`logIn` also sends `link: false` to `/identify` while the gate is closed, so the
server merges the anonymous purchases without copying the install link to the
signed-in customer.

**Remove the account from Support without a full reset**, e.g. when the user
withdraws consent:

```kotlin
AppRefer.support.clearIdentity() // suspend
```

This forgets the Support name, email and app user id, revokes the current Support
session (through the same durable revocation used by reset) and starts a new
anonymous session that carries no link to the old id: it sends neither the app user
id nor the install device id. A registered push token moves to the new session only
after the old session's revocation succeeds (offline, it waits for the next
successful retry), so the two sessions never hold it at once; a reset before then
means it is never moved. An open help screen closes. If a different user id is
stored later without `reset()`, Support revokes the anonymous session and starts a
fresh one for that account, so the next account never takes over the anonymous
conversations. Called before AppRefer has a `Context`, it is remembered and applied
on the next configure or Support use. **Conversations from the old
session are no longer visible in the app**; they stay in your team inbox. The app
user id is not sent to Support again until it changes (another account is stored
with `setUserId`/`configure(userId = …)`) or `reset()` runs; configuring with the
same user id on the next launch keeps Support anonymous. Attribution, Billing and the
stored user id are not changed. Combine it with `setCollectionEnabled(false)` to stop
attribution as well.

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
matching or event delivery; events tracked meanwhile are dropped, never sent later) and Support sends no device id until configure is
called again, so the next account's Support session is never linked to the
previous account's install. Support also omits the device id while collection is off.

Migration: after reset(), call configure again for the next user; until then events, attribution and consent reports are paused.

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

> **Status:** 0.9.3 — full attribution flow, event tracking, and Meta
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
    implementation("com.github.AppAgentic:apprefer-android-sdk:0.9.3")
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
import com.apprefer.sdk.billing.AppReferBilling  // only if you use AppRefer Billing
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

            // Your app's user id (the one you pass to AppReferBilling.logIn), so Support
            // conversations and product events carry the account. AppRefer Billing links
            // purchases to the ad click on its own; this isn't needed for that.
            AppRefer.setUserId(currentUser.id)
            AppReferBilling.logIn(currentUser.id)  // if you use AppRefer Billing
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

The AppRefer attribution device id is sent automatically as `appreferId`, so purchases are matched to the ad click without `setUserId` (only after `AppRefer.configure`, and not while collection is off or after `reset()` until configure runs again; see 0.9.2).

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
val check = AppRefer.support.verifyIdentity(tokenFromYourBackend) // 0.12.0, suspend: confirms the account (see "Verified identity" below)
AppRefer.support.accentColor = Color.rgb(120, 82, 255)
AppRefer.support.headerStyle = AppReferSupportHeaderStyle.PLAIN // 0.11.1: flat neutral grey header (default ACCENT); 0.11.2: Continue card avatar too
AppRefer.support.title = "My app support"     // defaults to "<application label> support" in the chat's language
AppRefer.support.replyTime = "a few hours"    // completes "We usually reply in …"; defaults to "under an hour", translated
AppRefer.support.teamNames = listOf("Jay")    // first names shown on the help home
AppRefer.support.appIcon = drawable           // defaults to the launcher icon, else the app's initials
AppRefer.support.compose("Problem with my subscription") // opens the help home, field pre-filled and focused

// Quick-help tiles (defaults: Billing & refunds, Restore purchase, Report a bug, Suggest an idea,
// translated; ids "billing", "restore", "bug", "idea").
// A tile starts a message with its title as the subject, or runs `action` instead. Empty hides them.
AppRefer.support.topics = listOf(
    AppReferSupportTopic.billing,
    AppReferSupportTopic.restore.copy(action = { restorePurchases() }),
    AppReferSupportTopic("Report a bug", "Screenshots welcome", R.drawable.ic_bug, Color.RED, "What went wrong?"),
)

// From a coroutine, after obtaining an FCM token, once AppRefer has verified your service account:
if (runCatching { AppRefer.support.status() }.getOrNull()?.pushReady == true) AppRefer.support.registerPushToken(token)
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
such as yellow; `headerStyle = AppReferSupportHeaderStyle.PLAIN` makes it a flat neutral grey with no
accent or glow instead, 0.11.1; from 0.11.2 the Continue card's avatar is neutral grey too), a "Continue" card for the open conversation (unread first, then the newest), the
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

### Verified identity (0.12.0)

The app user id the SDK sends with Support is only a claim from the device. To let your team and
the AI assistant use the account's private details (plan, purchases, orders, credits your backend
shares), confirm it:

1. Your backend, after signing the user in, calls
   `POST https://apprefer.com/api/v1/apps/{appId}/support/identity-tokens` with your **private**
   organisation key (`support:context` scope) and `{ "appUserId": "<the signed-in user>" }`. It
   returns a single-use `identityToken` valid for up to 10 minutes. Never put the private key in
   the app.
2. The app passes that token to ``AppRefer.support.verifyIdentity(token)` (suspend)` after `setUserId` with the same user.

It verifies the current Support session, so conversations already started on it count too; no new
conversation is needed. Network and server failures are retried with the same token and then
thrown (sending the same token again is safe: the server acknowledges a retry from the same session).
A refusal comes back as `verified: false` with a `reason` (`expired`, `already_used`,
`user_mismatch`, `session_replaced`… get a new token), or `account_changed` when a reset,
`clearIdentity()` or another user id happened while it ran. The token is never stored or logged.
`reset()`, `clearIdentity()` and another account signing in end the verification. Until an app
verifies, the AI assistant gets no account data at all, and your team sees the claimed account
marked "Not verified". `identify` is unchanged.

### Option cards (0.12.0)

Your team or AI assistant can attach one of your AppRefer Billing offerings to a reply. Tapping
**See options** on the card calls your code (on the main thread) with the offering, so you open
**your own** paywall:

```kotlin
AppReferSupport.onOffer = { offer ->
    // offer.offeringId, offer.packageId (nullable), offer.cardId, offer.conversationId
    PaywallActivity.start(context, offer.offeringId, offer.packageId)
}
```

The card reads "Options for you" (never "offer"): it can hold ordinary full-price plans, credit packs
or one-off purchases. Each price shows the store's own billing period ("£29.99/year"), never a
free-trial or introductory price; an owned one-off is tagged "Purchased", a current subscription
"Current plan".

The card lists the offering's packages with the store's own formatted prices (Google Play's regular recurring or one-time `formattedPrice` from `apprefer-billing`, when it is configured; otherwise no prices) and
tags the customer's current plan; switching plans is never blocked. The SDK never shows a paywall,
never starts a purchase and never shows a price, discount or deadline of its own: your paywall does
the buying and AppRefer Billing confirms it from the store as usual. A tap is reported as an
`opened` receipt (never a purchase); receipts made offline are kept on the device for up to 7 days
and sent again with the same id. Before calling you, the SDK asks AppRefer for up to 3 seconds
whether the card can still be opened: a withdrawn, expired or changed card redraws instead, and no
answer calls you anyway (the store is the real gate).

- `closesChatOnOffer` (default `true`) closes the chat Activity before `onOffer` runs.
- Without `onOffer`, cards say "Update the app to open this".
- Cards refresh when the chat opens, every 30 seconds while it is open, after a purchase, restore or
  sign-in reported by AppRefer Billing, and when the app comes back to the foreground.
- Your team sends cards from the inbox (＋ Offer); the AI assistant can attach one only for a
  verified customer. Offerings that aren't public in your catalogue (current, placement, targeting
  rule, running experiment) also need the customer verified (see Verified identity).
- `reset()` and `clearIdentity()` drop pending receipts with the old session.
- Older SDK versions show your text plus a line asking the customer to update the app.

### Review cards (0.12.0)

Your team can attach an optional **Write a review** card to a reply (inbox: ＋ Review link). The card
says "Share your experience" and opens the app's Google Play listing (`https://play.google.com/store/apps/details?id=<package>`), in the Play Store app when it is installed. There is nothing to set up: AppRefer builds the link
from the package name your app already has in AppRefer (Billing or the store URL in app settings).

- Neutral and optional: no stars, no "do you like the app?" question, no reward, never sent
  automatically and never only to happy customers. The AI assistant can only suggest one in a draft
  for a person to send.
- The SDK opens only `https://play.google.com/store/apps/details?id=<package>`; any other link from the server is shown as "no store page" and never opened.
- The SDK never calls the store's in-app review prompt from the card: that prompt is limited by the
  store and can silently show nothing, so both Apple and Google recommend a link for a button.
- A tap records an `opened` receipt (same queue as offer cards; dropped by `reset()`/`clearIdentity()`).
  It is never a review, a rating or a star count, and the chat never says a review was written.
- A withdrawn, expired or changed card (store page or settings changed) shows "This review link is no
  longer available"; a device with no store page for the app shows "There's no store page for this
  device". AppRefer sends at most one card per customer every 120 days (configurable, 30–365).
- Older SDK versions show your text plus a line asking the customer to update the app.

0.12.0 is unreleased source (not tagged). Once tagged, install it from Git only: JitPack, `com.github.AppAgentic.apprefer-android-sdk:apprefer-android-sdk:0.12.0` (core) and `com.github.AppAgentic.apprefer-android-sdk:apprefer-billing:0.12.0` (billing).

### Photo and video testimonials (0.13.0)

Ask a signed-in customer for a photo or a short video and give them the reward your campaign sets,
from your own screens, from your coach/assistant, from a Support reply, or automatically after
real usage. One native capture screen does it everywhere: what's asked, the reward, the system photo
picker (Android 13+ photo picker, else the document picker; no storage permission) or the camera app for a still photo or a video, a preview, an **Allow public use** switch with your exact permission
text (on unless the customer turns it off, and it says so), upload progress, then the result.

1. Your backend registers campaigns (`PUT /api/v1/apps/{appId}/support/testimonials/campaigns`,
   private key with `support:testimonials`). A company admin switches each one on in AppRefer
   Support; nothing reaches customers before that.
2. Your backend asks for one user: `POST /api/v1/apps/{appId}/support/testimonials/requests`
   `{ campaignId, appUserId, idempotencyKey, source: "host" | "coach" }`. It returns `requestId`.
   The reward comes from the campaign; the call can't set one.
3. The app shows it:

```kotlin
AppRefer.support.testimonials.onRewardUpdate = { update ->
    if (update.rewardStatus == "fulfilled") lifecycleScope.launch { credits.refresh() }
}
val open = AppRefer.support.testimonials.pending()                 // requests waiting for this account
AppRefer.support.testimonials.present(activity, requestId) { result ->
    Log.d("App", "${result.outcome} ${result.rewardStatus}")     // submitted, declined, cancelled, unavailable
}
```

4. Your backend applies the reward: `POST …/support/testimonials/rewards/claim` (2-minute leases),
   applies each `fulfillmentId` at most once in your own system (credits, coach usage,
   subscription time, a discount, a gift, or a merch order you collect the address for yourself),
   then `POST …/rewards/{fulfillmentId}/complete` with `status: "fulfilled"` and your own
   `receiptId`. Only then does the SDK report `fulfilled`.

- The customer's Support session must be verified for the same account
  (`verifyIdentity`); otherwise requests read as unavailable. A reset or account switch
  cancels a capture in progress; nothing is shared for another account.
- Taking part earns the reward whatever the customer says and whether or not the media is ever
  used. Public use is a separate yes/no: the switch starts on with your campaign's permission text
  and the customer can turn it off; only their final button records the choice ("Send and allow
  sharing" while on, "Send privately" while off; with the text's version), opening or cancelling records nothing, and it is revocable later
  (`withdrawPublication(requestId)`); the reward is never taken back.
- Photos are re-encoded as JPEG (≤ 2048 px, no location or other metadata); videos are exported as
  MP4 without metadata and refused when longer than the campaign allows. Limits: 15 MB per photo,
  150 MB per video. The server checks the stored bytes and video length again.
- Requests from Support show as a card in the chat ("Share a photo or video"); older SDK versions
  show your text and a line asking the customer to update the app.
- The Support home shows the newest open request for the verified account as a card each time it
  opens (never a pop-up). Requests your backend, coach or automation made appear there by
  themselves; shared, declined, withdrawn or ended ones leave it, and another account's never show.
- Automated asks (a campaign `automation`, switched on separately in Support): the invitation is that
  home card. The SDK declares the `testimonials` engagement capability and opens a tapped
  invitation notification (`/apprefer/testimonials/<requestId>`) as the capture screen itself,
  never through `onNotificationEvent`. AppRefer only records push invitations as dry runs in this
  release: no notification is sent yet.
- The reward line after sharing is honest about who confirms it: "earned" until your backend
  reports its receipt; then "added to your account" for credits, coach answers and subscription
  time, or "confirmed" for anything else (no shipping or email promise); a failed or late one says
  Support can see it and send it again.
- No new permission. "Take a photo" and "Record a video" use the camera app
  (`ACTION_IMAGE_CAPTURE` into a private cache file through the SDK's own non-exported provider, and
  `ACTION_VIDEO_CAPTURE`); the library merges the `<queries>` that let it find a camera app on
  Android 11+. Each is offered only when the device has a camera and a camera app. If your app
  itself declares `CAMERA`, Android refuses capture intents until it is granted, so the SDK asks for
  it with the system prompt at the moment of capture; declining keeps the library and says so.

0.13.0 is unreleased source (not tagged). Once tagged, install it from JitPack only: `com.github.AppAgentic.apprefer-android-sdk:apprefer-android-sdk:0.13.0` (and `apprefer-billing:0.13.0` if you use Billing; keep both on the same version).

### Languages and your own wording

The chat ships in 35 languages: ar, ca, cs, da, de, el, en, es, es-MX, fi, fr, he, hi, hr, hu, id, it, ja, ko, ms, nb, nl, pl, pt-BR, pt-PT, ro, ru, sk, sv, th, tr, uk, vi, zh-Hans, zh-Hant. They are ordinary string resources (`res/values-xx/apprefer_support_strings.xml`, plurals included). Nothing to set up.

- **Which language:** the one your app is running in: its per-app language (Android 13+ `LocaleManager`, or a locale your app applies to its resources), limited to the languages your app declares in `android:localeConfig` when it declares any. So an app that declares only English shows an English chat on a German phone. Without a `localeConfig`, the app's locale list is used as is (normally the device's). If the SDK has no table for it, the device's languages are tried, then English. Regional variants map sensibly: Latin American Spanish → es-MX, Portuguese outside Brazil → pt-PT, Chinese by script and region, Norwegian → nb, the legacy `iw`/`in` codes → he/id.
- **Force a language:** `AppRefer.support.language = "de"` (any shipped code; `null` follows the app again).
- **Right to left:** in Arabic and Hebrew the chat lays out right to left, bubble tails and the back arrow included. This needs `android:supportsRtl="true"` on your `<application>` (the Android Studio default); without it Android keeps every layout left to right.
- **Your own wording:** set any key; your text wins over the SDK's translation in every language. Placeholders are written `{name}` and filled in by name, so you can reorder them.

```kotlin
AppRefer.support.strings = mapOf(
    "support.home.subtitle" to "What can we fix for you?",
    "support.presence.team" to "Real humans · usually {time}",
    "support.a11y.unread#one" to "One new reply",   // plural keys: "#one", "#few", … or the bare key for every count
)
// For your own app languages, set the values for the language you're showing:
if (resources.configuration.locales[0].language == "de") AppRefer.support.strings = mapOf("support.home.subtitle" to "Wie können wir helfen?")
```

Overriding a string resource also works the Android way: define `apprefer_support_home_subtitle` (the key with an `apprefer_` prefix, dots and capitals turned into underscores) in your app's own `values-de/strings.xml`, and the app's value wins for that language.

`title`, `replyTime` and topic texts you set are shown as written. The defaults are translated, and `replyTime` is inserted into a translated sentence: in most languages that sentence already carries its own "in", so give the whole phrase (German `"innerhalb weniger Stunden"`, French `"en quelques heures"`). The built-in tiles (`AppReferSupportTopic.billing`, `.restore`, `.bug`, `.idea`) translate themselves; their title becomes the conversation subject in the customer's language, and `copy(...)` keeps the text you don't pass translated. The notification channel `apprefer_support` is named in the chat's language when Support is configured.

Two Android details: with an App Bundle, Google Play installs only the device's languages, so a forced language the device doesn't list (or an in-app choice on Android 12 and older) may be missing and shows English; disable language splits (`bundle { language { enableSplit = false } }`) if you force languages. If your build keeps only some languages (`localeFilters` / `resConfigs`), the chat's other translations are removed too and those languages show English.

Every key, its English text, a note for translators and all translations are in [`Localization/support-strings.json`](Localization/support-strings.json), the table all four AppRefer SDKs share; `scripts/generate-support-strings.py` regenerates the resources from it. Messages your team writes and the AI assistant's answers and hand-over line come from AppRefer, not from this table.

### Help centre and status (0.10.0)

Publish help articles in AppRefer (dashboard, API or MCP) and they appear in the chat with no code: a "Help articles" card on the help home (search and the first three; "See all" with more), a help centre screen (articles by category, search that filters as you type and then asks the server, which also searches article bodies) and a reader. The reader renders markdown (headings, lists, bold and italic, code, quotes, links and https images) with a small built-in parser and Android spans; HTML is never run and only http(s) and mailto links are kept, opened in the browser or mail app. "Still need help? Message us" goes back to the message field with the article's title as the conversation subject. Apps without published articles see no help section at all. Articles come in the chat's language (the best translation the server has); lists are kept in memory for five minutes, and offline the chat shows what it had, never an error screen. Test keys (`pk_test_`) also show drafts, marked "Draft". The screen on show survives rotation.

```kotlin
// Open straight on the help centre or one article (any translation's slug works)
AppRefer.support.openHelpCentre()
AppRefer.support.openArticle("restore-purchases")
AppReferSupportView(context, destination = AppReferSupportDestination.Article("restore-purchases")) // or HelpCentre / Conversation(id) / Home

// Your own help screens (suspend functions)
val articles = AppRefer.support.articles()              // List<AppReferHelpArticle>: slug, title, summary, category, url, updatedAt, isDraft
val results = AppRefer.support.articles("refund")       // server search (titles, summaries, bodies), up to 20
try {
    val article = AppRefer.support.article("refunds")   // AppReferHelpArticleDetail: + body (markdown)
} catch (e: AppReferSupportException) { if (e.isNotFound) { /* removed or unpublished */ } }

// Is Support ready?
val status = AppRefer.support.status()
status.pushReady            // FCM configured and Google accepted the service account
status.emailReplies         // customers can answer reply emails
status.helpCentreArticles   // published articles
```

`status()` follows the server's caching (5 minutes once every configured channel is verified, else 30 seconds), so calling it on every launch is cheap. Register push tokens only when `pushReady` is true. The line under a customer's last message follows the same status: "we'll notify you" when push is ready and the app registered, "we'll email you" when replies are emailed and the customer gave an email in `identify`, otherwise "the team's reply will appear here".

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
- It checks on launch, when the app becomes active, after trial or revenue events and after AppReferBilling purchases, for the 35 days Apple's postbacks cover. If you take payments outside AppReferBilling, call `await AppRefer.updateConversionValue()` after a purchase to set it sooner.
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

Call this when collection consent is withdrawn. The choice persists across launches. Disabling cancels in-flight attribution, conversion-value, consent and product-event traffic, clears the durable event queue, and blocks activation/background retries. Configure returns cached attribution (or an unavailable/failed result if none exists) while disabled. Support and Billing continue independently. Call `setCollectionEnabled(true)` and then configure again to resume; enabling alone sends nothing. Calling it with `true` while collection is already on changes nothing (no pause, no queue clear). `configure(userId = ...)` still stores the user id while collection is off, so Support uses that account. It never throws; if the choice can't be saved, collection still stops for this session. Reset does not change this choice.

Product events include `app_user_id` from the identity set via `setUserId` when tracked. The identity is saved in the queued payload, so retries and relaunches retain the original account even after a user switch. Without an identity the field is omitted. Internal events whose names start with `_` retain their existing payloads.

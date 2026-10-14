# Changelog

All notable changes to the AppRefer Android SDK will be documented in this
file. This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and stays version-locked with the iOS, Flutter, and React Native SDKs.

## [Unreleased]

### Added
- AppRefer Billing (`apprefer-billing` module, `com.apprefer.sdk.billing.AppReferBilling`):
  Google Play purchases and entitlements verified by AppRefer, replacing RevenueCat.
  Mirrors the iOS `AppReferBilling` API.
  - `configure(context, apiKey?, appUserId?, entitlementsByProduct, defaultEntitlement?, consumableProductIds)`
    with an anonymous `$ARAnon:<UUID>` id kept on device
  - `products(ids)`, `purchase(activity, product, offerToken?, oldProductId?, replacementMode)`
    (sets `obfuscatedAccountId` to the AppRefer account token), `restorePurchases()`
  - `customerInfo(fetchPolicy)`, `customerInfoStream` (Flow), `listener`;
    `entitlements["premium"]?.isActive`, `source` (`SERVER` / `CACHE` / `LOCAL_FALLBACK`)
  - `logIn` (merges anonymous purchases via `/identify`), `logOut`, `setAttributes`,
    `setEmail` / `setDisplayName` / `setPhoneNumber`; Java callback overloads
  - Play Billing Library 8.3: pending purchases, automatic service reconnection,
    `queryPurchasesAsync` for restore, sync and outage fallback; consumables consumed
    after AppRefer records them; purchases AppRefer couldn't take are acknowledged on
    device as soon as they are queued, and failed acknowledge/consume is retried on foreground
  - Outage safety: Google Play's active purchases merged with the cached server answer;
    the user's own Play purchases are also merged into server answers; disk retry queue
    (50 attempts / 90 days) with back-off on 401/403/404/409/429 and `Retry-After`
  - `restorePurchases()` sends `"restore": true` (the only call that may move another
    user's purchase); pending identifies survive `logOut`
- Attribution: an unreadable stored device id is no longer replaced with a new one
  (the configure request is skipped and retried next launch).
  - Sends the AppRefer attribution device id as `appreferId` automatically
- `InternalAppReferApi` / `AppReferInternal` (opt-in, for AppRefer modules only).

### Changed
- Built with Kotlin 2.2.20. The attribution module still targets Kotlin language/API 2.0
  and stdlib 2.0.21, so attribution-only apps on Kotlin 2.0 are unaffected.
- JitPack now publishes two modules. `com.github.AppAgentic:apprefer-android-sdk:<tag>`
  brings in both; attribution-only apps should use
  `com.github.AppAgentic.apprefer-android-sdk:apprefer-android-sdk:<tag>`.
- Sample app: `minSdk` 23 and a billing entitlement check.

## [0.4.1] - 2026-04-23

### Added
- Full `configure()` attribution flow: Install Referrer, GAID, device
  signals — POSTed to `/api/track/configure`, cached locally, kill-switch
  respected.
- `trackEvent(eventName, properties?, revenue?, currency?)` — POSTs to
  `/api/track/event`. Java callback variant via `AppReferCallback`.
- `setAdvancedMatching(email?, phone?, firstName?, lastName?, dateOfBirth?)`
  — SHA256-hashes all PII on-device (byte-for-byte parity with the iOS
  `AppReferHashing` and Flutter `AppReferHashing` implementations) and
  sends as a `_advanced_matching` event. Java callback variant.
- `setUserId(userId)` — persists locally and syncs to the server via
  `_set_user_id` so webhook userId fallback lookups succeed. Java callback
  variant.
- R8 minification enabled on the sample `:release` build to exercise our
  `consumer-rules.pro` on every CI run.
- Fuzz test button in the sample — passes 100 KB event names + 1 MB byte
  payloads + nested maps to prove crash-free behavior.
- Vanniktech Maven publish plugin wired for Sonatype Central (signing is
  gated on `signingInMemoryKey` so `publishToMavenLocal` works without
  credentials). Publish workflow staged at `PUBLISH_WORKFLOW.yml.pending`.

### Changed
- `consumer-rules.pro` gains `-dontwarn com.google.android.gms.ads.identifier.**`
  for apps that strip Play Services ads-identifier.

### Notes
- Maven Central artifact will resolve once the `com.apprefer` Sonatype
  namespace is claimed. Config is ready; build from source until then.
- All public API entry points wrap `SafeRunner.safely { }` — the SDK
  MUST NEVER crash the host app.

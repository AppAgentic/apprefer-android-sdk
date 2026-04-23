# Changelog

All notable changes to the AppRefer Android SDK will be documented in this
file. This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and stays version-locked with the iOS, Flutter, and React Native SDKs.

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

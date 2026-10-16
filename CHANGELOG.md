## 0.9.3

- Billing's account gate survives a relaunch. reset() saves the Billing app user id that was current at that moment (`apprefer_billing_reset_user`, in Billing's own storage). While that is still the current Billing user, Billing sends no install device id (`appreferId`) or device attributes with customers/purchases requests, never re-links that user, and `/identify` sends `link: false`, even after the app restarts and configure runs again. It is cleared by any AppReferBilling.logIn(…) (even with the same id) or logOut(), and when the Billing user id changes to a different one. Nothing is saved if Billing has never stored a user on the install.
- Re-registering a push token that an old Support session still holds while it waits for revocation (after reset(), clearIdentity(), or saved by an earlier version) no longer posts it at once: that revocation is flagged to move the token, and it is registered on the current session once the old session is revoked. A token no pending revocation holds is still posted immediately.
- A Support revocation requested while a revocation pass is running is processed in the same app session: the running pass runs again when it finishes. Android already picked up most of these; this closes the last gap, at the very end of a pass.
- The cleared-session marker was already stored next to the Support session token on Android (both are removed together on uninstall); no change.

## 0.9.2

- Billing sends no install device id (`appreferId`) or device attribute (`$gpsAdId`, `$fbAnonId`, `$deviceVersion`, `$ip`) with customers/purchases requests while collection is off (saved, unsaved or paused waiting for configure), after reset() until configure/configureResult runs again, or in Billing-only apps. It reuses the Support device id gate (new `AppReferInternal.linkableDeviceId()` / `installLinkAllowed()`); queued purchases are resent without it while the gate is closed, and Billing then uses apprefer.com rather than trk.apprefer.com. Purchases, restore, entitlements and offerings are unchanged.
- Add `AppRefer.support.clearIdentity()`: forgets the Support name, email and app user id, revokes the current Support session through the durable revocation path and starts a new anonymous session (push token carried over). Conversations from the old session are no longer visible in the app; they stay in the team inbox. Support stays anonymous until the user id changes or reset() runs.
- Review fixes:
  - The anonymous Support session after clearIdentity() sends no device id (it would link the session back to the account).
  - A session minted while the identity was cleared is never given an app user id: when a different user id is stored without reset(), it is revoked and a fresh session is minted for that account (in-flight Support calls are not cancelled). The marker is persisted next to the session token.
  - The push token moves to the new session only after the old session's revocation succeeds (never when the revocation is dropped for age or attempts, and never after a reset). clearIdentity() no longer registers it itself, and registerPushToken() with a token still waiting to move only saves it.
  - Billing sends the device id only for the Billing user set after the last reset(): call AppReferBilling.logIn/logOut for the next account. Requests for an earlier user, or a purchase owned by someone other than the current Billing user (queued entries included), carry none, and users registered before the reset are not re-linked. New `AppReferInternal.accountEpoch()`.
  - `/identify` sends `link` (false while collection is off, unsaved or awaiting configure, after reset() until configure, and in Billing-only apps), so the server skips copying the install link.
  - clearIdentity() before AppRefer has a Context is no longer a silent no-op: it clears the name and email, logs a warning, and is applied (flag saved, session retired) on the next configure or Support use.

## 0.9.1

- Migration: after reset(), call configure again for the next user; until then events, attribution and consent reports are paused.
- If setCollectionEnabled(false) runs before configure and the choice cannot be saved, the "off" is kept for the rest of the process, so the next configure does not resume collection and Support sends no device id.
- Product events capture `app_user_id` at track time and persist it with the queued payload, preserving identity across retries, restarts, and account switches. Internal `_` events are unchanged.
- Add persistent `setCollectionEnabled` control. Disabling cancels attribution, consent, conversion-value and event requests and clears queued events; Support and Billing remain available. Enable, then call configure to resume.
- Support sends no device id after reset() or while collection is off. reset() also stops attribution (consent re-reports, user id, advanced matching, event sends) until configure is called again; events tracked meanwhile are dropped, never queued or sent later.
- setCollectionEnabled(true) while collection is already on is a no-op: it no longer pauses collection or clears the saved event queue. Only a saved "off" or the server kill switch clears queued events.
- configure(userId:) stores the user id (and refreshes Support's identity) even while collection is off, so Support uses the new account.
- setCollectionEnabled never throws (no context or a failed save included); disabling stops and clears at once even if the choice can't be saved. The choice is saved before collection is paused, so no configure can start in between and send a request.

## 0.9.0 — 2026-10-16

- Add async reset for account switching: cancel old work, revoke Support sessions and push registrations, clear account/profile/UI state and pending product events, retain install attribution and device id. Billing logout stays explicit.
- Add Support-only configuration with no attribution initialization or analytics.
- Persist a bounded 1,000-event / seven-day product queue with stable eventId, network/5xx backoff and launch/foreground retries; remove successful and terminal rejected events. Delivery is at least once.
- Add trackEventResult acknowledgements and clearPendingEvents; retain the existing tracking signature and respect the existing SDK kill switch.
- Add typed configureResult distinguishing attributed, confirmed organic and failure, while preserving configure.
- Add account isolation, persistence, expiry, retry, terminal rejection, cancellation and attribution-status regressions.

## 0.7.0 - 2026-10-16

## 0.8.0 — 2026-10-16

- Add RevenueCat-style Offerings, packages, placements, metadata and experiment context, with native store product resolution.
- Add five-minute memory/disk caching, shared in-flight requests, foreground refresh, identity invalidation and offline catalogue fallback.
- Add package purchases with durable offering attribution and a stable anonymous install bucket.
- Include the 0.7.1 offline fallback fix: explicitly empty entitlement mappings unlock nothing; only unmapped products use the default.
- Add paywall examples and Offerings regression tests.

- Version alignment with iOS 0.7.0 and its SKAdNetwork / AdAttributionKit conversion values.
- New `AppRefer.updateConversionValue()` for API parity: iOS-only, a no-op on Android.
- README: iOS Info.plist keys to receive postback copies (`NSAdvertisingAttributionReportEndpoint`, `AdAttributionKit.AttributionCopyEndpoint`).

## 0.6.0

- Split tracking and first-party requests by current consent, including billing; known consent never falls back across hosts. Support remains on apprefer.com.
- Add public setTrackingConsent and trackingConsentDidChange, persisted Android choices, automatic activation checks and retryable consent configure reports after first-open matching.
- Read iOS ATT without prompting; gate IDFA/GAID and drop advanced matching locally without consent.
- iOS apps declare only trk.apprefer.com in NSPrivacyTrackingDomains and call trackingConsentDidChange() after their own ATT prompt. Android apps use setTrackingConsent(true/false), or null for legacy routing.

## [0.5.0] - 2026-10-15

- AI personas: approved name and portrait, explicit AI tag, and a hand-over pill when a person is needed.
- Add native AppRefer Support home, conversations, screenshot uploads, session recovery and reply push integration.
- Support chat "Concierge" design, matching iOS: accent hero with presence pill, Continue card, quick-help
  topics, floating message field on the home (no separate compose screen), grouped gradient bubbles,
  quick replies and a full-screen screenshot viewer. New `replyTime` (replaces `replyTimeText`), `teamNames`,
  `topics` (`AppReferSupportTopic`, optional `action`) and `appIcon`; a picked topic becomes the conversation
  subject; `compose(text)` opens the home with the field pre-filled.

### Billing and attribution

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
  - Rejected-purchase archive: a final 400/413/422, missing store data or a queue give-up
    is saved to disk before the purchase is acknowledged (never consumed);
    `rejectedPurchases()` lists them and `restorePurchases()` resends them
  - Purchase intents: who started a purchase is saved before Google Play opens (or the
    purchase is not started), so pending purchases completing after a restart, `logOut`
    or user switch are recorded for them
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

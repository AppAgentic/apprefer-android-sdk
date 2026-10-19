## 0.13.0 (unreleased)

- Support photo/video testimonials: `AppRefer.support.testimonials` (`pending()`, `request()`, `present(context, requestId, completion)`, `rewardStatus()`, `withdrawPublication()`, `cameraAvailable()`, `onRewardUpdate`) and `AppReferTestimonialActivity` (registered in the library manifest); the chat draws request cards. Photo picker / camera-app capture, JPEG and metadata-free MP4 remux (`TestimonialMedia.java`, shared by copy with the Flutter plugin and React Native module). A request is made only by something the app trusts (its backend or coach tool with a private key, a person in Support, the AI within the app's policy, automation); the SDK answers it for the account its support session is verified for, shows the campaign's reward and exact public-use permission text, uploads to a presigned URL (`POST /api/support/v1/testimonials/{id}/uploads`, then `…/submit` with `{ uploadId, clientSubmissionId, publication, permissionVersion }`), and reports the reward state the app's backend confirms. Retries reuse the same upload and client id; a reset or account switch cancels a capture in progress. Requests announce `testimonials` in `X-AppRefer-Support-Features`. 23 new `support.testimonial.*` strings in all 35 languages (161-key table). No new dependency.
- Testimonial capture on Android also takes a still photo: "Take a photo" (`ACTION_IMAGE_CAPTURE` with `EXTRA_OUTPUT` into the SDK's private cache file through the non-exported `TestimonialMedia$CaptureProvider`, a one-off write grant, no androidx dependency) beside "Record a video". The photo is normalised like a picked one (orientation applied, EXIF/GPS dropped) and the raw file deleted on success, cancel or failure; the target survives activity recreation. No permission declared; when the app itself declares `CAMERA` the SDK requests it at capture time, and a refusal is explained while the library stays offered. `cameraAvailable(context, video)` per kind; the library manifest adds the camera-intent `<queries>` for Android 11+. Shared `TestimonialMedia.java` updated (Flutter and React Native copies in sync).
- Testimonial invitations: the Support home reads the verified account's open requests when shown (at most once a minute, again on resume and after the capture screen closes) and shows the newest as a card leading to the capture screen; an account reset clears them first. A tapped engagement notification for `/apprefer/testimonials/<requestId>` opens the capture screen and is never handed to `onNotificationEvent`; installs declare the `testimonials` capability.
- Testimonial copy: honest "good, bad or mixed" line, public reuse on by default (the customer can turn it off, and the screen says so) with the same reward either way, a clear "nothing was sent" retry, and a reward line by status and type: earned until the backend's receipt, "added to your account" only for credits/coach answers/subscription time, otherwise "confirmed"; failed/late and already-rewarded states. 7 new `support.testimonial.*` strings, 5 rewritten, all 35 languages (168-key table).
- Version 0.13.0 (prerelease source; not tagged or released).

## 0.12.0 (unreleased)

- Support option cards copy (reviewed with @copy): neutral wording for ordinary full-price plans, credit packs and one-off purchases ("Options for you", "See options", "These options are no longer available", "Update the app to open this"); each price shows the store's own billing period ("£29.99/year") from Google Play's recurring pricing phase `billingPeriod`, never a free-trial or introductory phase; one-time purchases and periods outside week/4 weeks/month/2, 3, 6 months/year show the bare store price; an owned one-off is tagged "Purchased" ("Current plan" stays for subscriptions). 8 new strings in all 35 languages (138-key table). `AppReferInternal.SupportStorePrice` replaces the internal price pair between `apprefer-billing` and the core module (keep both on the same version).
- Support review cards: an optional "Write a review" card the team can attach to a reply. It opens the app's Google Play listing (`https://play.google.com/store/apps/details?id=<package>`, in the Play Store app when installed) after the same ≤ 3 s `opened` receipt check as offer cards (`POST …/reviews/{cardId}/events`; refresh `GET …/conversations/{id}/reviews`), as Google recommends for a button instead of the quota-limited In-App Review API. Only that exact URL shape is opened; no stars, never claims a review was written. Requests announce `reviews` in `X-AppRefer-Support-Features`. Neutral copy in 35 languages (8 new `support.review.*` keys, 130-key table). No new dependency.
- Support offer cards: a team member or the AI assistant can attach one of the app's AppRefer Billing offerings to a reply. The chat draws a card (title, packages with the store's own formatted prices when AppRefer Billing is configured, "Current plan" tag, "View offer") in the Concierge style, light and dark, and calls the new `onOffer` callback with `{ cardId, conversationId, offeringId, packageId }` so the app opens its own paywall (`closesChatOnOffer`, default true, closes the chat first). No paywall, purchase, price, discount or deadline of the SDK's own; a tap is an `opened` receipt, never a purchase. Disabled states: owned, no longer available, and "can't be opened in this version" when the app sets no `onOffer`. Requests send `X-AppRefer-Support-Features: offers[, offer-handler]` and the thread asks for `?platform=`; cards refresh on open, every 30 s, after Billing changes and on foreground (`GET …/conversations/{id}/offers`). `shown`/`opened` receipts (`POST …/offers/{cardId}/events`) are queued on the device (7 days, 8 attempts, same id on every retry) and dropped by `reset()`/`clearIdentity()`; an `opened` tap waits at most 3 s for the server and redraws a withdrawn card instead of opening the paywall. 15 new `support.offer.*` strings in all 35 languages (122-key table). `apprefer-billing` supplies Play prices and Billing change notices to Support through `AppReferInternal`.
- Support: `verifyIdentity(identityToken)` confirms who the customer is with a short-lived, single-use token your backend gets from AppRefer (`POST /api/v1/apps/{appId}/support/identity-tokens`, private key, never in the app). It verifies the current Support session (`POST /api/support/v1/session/identity`), so existing conversations on it count; returns `{ verified, reason, appUserId }`. Network and server failures are retried with the same token (the server acknowledges a retry from the same session), then thrown. A reset, `clearIdentity()` or a different user id while it runs answers `account_changed`; the token is never stored or logged. After `clearIdentity()` nothing is sent (`identity_cleared`). `identify(name, email)` is unchanged.
- Version 0.12.0 (prerelease source for the actionable Support roadmap; not tagged or released).

## 0.11.2 (unreleased)

- Support: `PLAIN` header style now also draws the avatar on the home's Continue card (the chat icon, or the first team member's initial) in the header's neutral greys (`#6B6B6B` to `#3A3A3A`, white glyph ≥ 5:1) instead of the accent's neighbours, which were pale mint for a white accent. The AI persona's portrait still covers it. No new API; the default accent style is unchanged, and the help tiles, conversation list and chat are untouched.
- Version 0.11.2. No other change.

## 0.11.1 (unreleased)

- Support: `AppRefer.support.headerStyle` (`AppReferSupportHeaderStyle.ACCENT` default, `PLAIN`). `PLAIN` draws the help home's header as one flat neutral grey (dark `#1C1C1C`, light `#EBEBEB`) with white / near-black text and matching status bar icons: no accent fill, no radial glows, neutral presence pill, close button, initials tile and presence avatars (the AI portrait still shows). For apps whose design has no colour accents, where even a white `accentColor` gave the hero tinted glows. `ACCENT` is unchanged; bubbles, buttons and the rest of the chat keep `accentColor` in both.
- Version 0.11.1. No other change: lifecycle engagement stays off / dry run, Billing and attribution are untouched.

## 0.11.0 (unreleased)

- Lifecycle engagement client: `AppRefer.engagement` (`start`, `setPushToken`, `refreshDeviceState`, `notificationPermission`, `channelEnabled`, `preferences`/`setPreferences`, `identify(assertion)`, `logout`, `deleteInstall`, `handleMessage(context, data)` for your FirebaseMessagingService, `handleIntent(context, intent)` for your launch activity, `onNotificationEvent`, `setNavigationReady`). Off unless the app starts it and AppRefer enabled it for the app (dry run on the server in this release).
- The SDK renders AppRefer's FCM data messages itself with platform APIs (no Firebase or AndroidX dependency): one channel per purpose (names in 35 languages), BigText or a BigPicture image from allowlisted HTTPS hosts (no redirects, PNG/JPEG checked against the bytes, size/time/pixel limits, text on any failure), up to two buttons, explicit immutable PendingIntents with a random per-notification nonce (forged intents open nothing), snooze through an inexact alarm, time-out at expiry. Nothing is shown, or reported as received, for another install or account, when expired, when the purpose channel is off, or without notification permission. API 21+ guards; Android 13 permission and channel readback; no permission is requested.
- Install credential encrypted with an Android Keystore key in no-backup storage (RSA-wrapped on API 21–22).
- Product events carry `X-AppRefer-Install` only as described for iOS; `AppRefer.reset()` logs engagement out.
- New strings `engagement.action.*`, `engagement.channel.*` in all 35 languages. Manifest adds a non-exported receiver.
- Review fixes (before release): queued events carry the account epoch they were queued under (in their stamp, and as `X-AppRefer-Install-Epoch` with the credential), and the last confirmed epoch is persisted per install, so an event queued under one account never goes out for another after a cold restart, a backend bind or a logout; preference changes, sign-in and logout send the epoch they were made for (`expectedEpoch`, required by the server) and a stale one is refused (`identityChanged`; a stale saved logout is settled, never applied to the next account); reading preferences learns their epoch; an expired notification never navigates, even when its tap waited for `setNavigationReady`; sources shared by copy with the other SDKs are hash-checked by this repo's tests (`shared-sources.json`).
- The renderer re-checks identity, the real clock, permission and the purpose channel immediately before posting (after any image download), under the same lock identity changes take, and cancels AppRefer's posted notifications when the identity changes. Expired notifications never open, snooze, re-show or report anything (Android 7.1 and older keep them in the shade without a time-out).
- Version 0.11.0.

## 0.10.1 (unreleased)

- Version 0.10.1, for parity with the iOS, Flutter and React Native SDKs (iOS Support push now reads the APNs environment from the app's signing). No Android behaviour change: FCM has no sandbox/production split.

## 0.10.0 (unreleased)

- Help centre in the Support chat. With published articles, the help home shows a "Help articles" card (search entry, top three, "See all"); a help centre screen lists every article by category with search (instant local filter, then the server's search 300 ms after the last keystroke); an article reader renders markdown with a small pure-Kotlin parser (`SupportMarkdown`: headings, setext headings, ordered/unordered/nested/task lists, quotes, fenced code, rules, https images; inline bold, italic, code, strikethrough, links) turned into Android spans. HTML is stripped (script/style with their content) and links keep only http(s) and mailto, opened with `ACTION_VIEW`. "Still need help? Message us" returns to the message field with the article's title as the subject. No articles: no section. Concierge design, 35 languages, RTL. The help screen on show (and the search) survives Activity recreation.
- New suspend `AppRefer.support.articles(query)`, `article(slug)` (throws `AppReferSupportException`, `isNotFound` on 404) and `status()` (`AppReferSupportStatus`: `pushReady` for Android, `emailReplies`, `helpCentreArticles`, raw push/email fields). Help requests need only the SDK key (no support session); lists and articles are cached in memory for five minutes, with the last copy used offline; the status honours the server's Cache-Control (5 min / 30 s).
- New `AppRefer.support.openArticle(slug)`, `openHelpCentre()` (with `Context` overloads) and `AppReferSupportView(context, destination = AppReferSupportDestination.…)` (`Home`, `HelpCentre`, `Article(slug)`, `Conversation(id)`); Back always leads to the help home.
- The line under the customer's last message says how the reply will reach them: notification (push ready and registered), email (replies emailed and an email was given), or "the team's reply will appear here".
- Fix: no API 24+ collection methods on older devices. `ConcurrentHashMap.newKeySet()` (Support and core HTTP clients, minSdk 21) and `Map.putIfAbsent()` (Billing product lookup, minSdk 23) are replaced with API 21-safe equivalents; a unit test keeps them out.
- 22 new strings (`support.help.*`, `support.status.*`) in all 35 languages; the shared table now has 100 keys. New vector drawables `apprefer_support_article`, `_search`, `_open`, `_chevron`.
- Version 0.10.0.
- Support chat localized into 35 languages (ar, ca, cs, da, de, el, en, es, es-MX, fi, fr, he, hi, hr, hu, id, it, ja, ko, ms, nb, nl, pl, pt-BR, pt-PT, ro, ru, sk, sv, th, tr, uk, vi, zh-Hans, zh-Hant): every title, button, placeholder, quick-help tile, status line, error and accessibility label, plus the `apprefer_support` notification channel name. Strings ship as Android string resources (`res/values-xx/apprefer_support_strings.xml`, with `<plurals>`; Hebrew and Indonesian in `values-iw`/`values-in`, Chinese in `values-b+zh+Hans`/`values-b+zh+Hant`), generated by `scripts/generate-support-strings.py` from `Localization/support-strings.json`, the 78-key table shared with the iOS, Flutter and React Native SDKs. Plural labels use the platform's rules (ICU, Android 7+; the SDK's CLDR rules below that).
- The chat follows the language the host app is running in (its per-app language or resource locale, limited to the languages it declares in `localeConfig` when it declares any), then the device's languages, then English, so an app that ships fewer languages gets a chat that matches it. `AppRefer.support.language` forces one.
- New `AppRefer.support.strings: Map<String, String>`: your text for any key, used before the bundled translations in every language (`{placeholders}` filled by name; plural keys as `key#one` etc. or the bare key). Overriding a resource (`apprefer_support_…`) in your app's own `values-xx` also works.
- `replyTime`, `title` and the built-in topics (`AppReferSupportTopic.billing`, `.restore`, `.bug`, `.idea`) default to the chat's language; values you set are shown as written. `replyTime` is still a `String` property. Topics have a new `id`: `billing`, `restore`, `bug`, `idea` for the built-in ones, the title for yours. `AppReferSupportTopic` is no longer a data class (its title is read in the chat's language); the public constructor, `equals` and `copy(...)` remain, `componentN()` is gone.
- Errors in the chat are shown in the customer's language (offline, rate limit, message too long, upload failed, load failed, not sent, image unreadable) instead of the server's English message. `AppReferSupportException.message` still carries the raw message for logs. A conversation that fails to load offers Try again.
- Right-to-left: Arabic and Hebrew lay the chat out right to left (needs `android:supportsRtl="true"` in the app), including the bubble tails, the back arrow, paddings and screenshot rows; dates and times use the chat's language and the device's 12/24-hour setting ("Today, …"/"Yesterday, …" are translated).
- README: RevenueCat examples replaced with AppRefer Billing ones.

## 0.9.3

- Billing's account gate survives a relaunch. reset() saves the Billing app user id that was current at that moment (`apprefer_billing_reset_user`, in Billing's own storage). While that is still the current Billing user, Billing sends no install device id (`appreferId`) or device attributes with customers/purchases requests, never re-links that user, and `/identify` sends `link: false`, even after the app restarts and configure runs again. It is cleared by any AppReferBilling.logIn(…) (even with the same id) or logOut(), and when the Billing user id changes to a different one. Nothing is saved if Billing has never stored a user on the install.
- reset() does not mark the fresh anonymous id that AppReferBilling.logOut() minted in this process (apps often sign out of Billing before reset(), or don't await reset()). The exemption is used once: a second reset() while that anonymous user is still current marks them; a later logIn or another logOut replaces it. A marker write from a reset() that lands after a logIn/logOut started since that reset is dropped. A user set with logIn is still marked by a later reset().
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

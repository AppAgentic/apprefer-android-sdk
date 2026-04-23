# Changelog

All notable changes to the AppRefer Android SDK will be documented in this
file. This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and stays version-locked with the iOS, Flutter, and React Native SDKs.

## [0.4.1] - 2026-04-23

### Added
- Initial Phase 1 skeleton.
- Kotlin public API surface: `AppRefer.configure`, `trackEvent`,
  `setAdvancedMatching`, `setUserId`, `getAttribution`, `getDeviceId`.
- `Attribution` data class.
- `AppReferCallback<T>` Java-friendly interface.
- Sample app module that calls `configure()` and renders the result.
- CI workflow (assemble + lint).

### Notes
- All public methods are no-ops in this release. `configure()` returns an
  organic `Attribution` so host apps can integrate and compile end-to-end.
  Phase 2 (device ID, HTTP calls, install referrer, real attribution flow)
  lands in a follow-up release.

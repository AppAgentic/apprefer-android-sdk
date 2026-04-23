# AppRefer SDK — consumer R8/ProGuard rules.
#
# These rules are automatically applied to any app that depends on the SDK.
# Keep rules must cover:
#   - The public `AppRefer` object + its callback interface so host apps can
#     reference them from Java/Kotlin after minification.
#   - The `Attribution` model class (its fields are read reflectively by the
#     host app and serialized back out by our own codec).
#   - The generated top-level `Version.kt` class (`APPREFER_SDK_VERSIONKt`)
#     that holds our `const val APPREFER_SDK_VERSION`.
#   - `-dontwarn` for the optional Google Play Services / Install Referrer
#     classes we reference reflectively — some host apps exclude one or the
#     other, and R8 would otherwise fail the build.
-keep class com.apprefer.sdk.AppRefer { *; }
-keep class com.apprefer.sdk.AppReferCallback { *; }
-keep class com.apprefer.sdk.models.Attribution { *; }
-keep class com.apprefer.sdk.APPREFER_SDK_VERSIONKt { *; }
-dontwarn com.android.installreferrer.**
-dontwarn com.google.android.gms.ads.identifier.**

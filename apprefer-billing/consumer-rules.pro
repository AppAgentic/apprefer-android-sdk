# AppRefer Billing — consumer R8/ProGuard rules.
#
# Public API used by host apps. Play Billing ships its own consumer rules.
-keep class com.apprefer.sdk.billing.AppReferBilling { *; }
-keep class com.apprefer.sdk.billing.AppReferBilling$* { *; }
-keep class com.apprefer.sdk.billing.AppReferBillingListener { *; }
-keep class com.apprefer.sdk.billing.AppReferBillingException { *; }
-keep class com.apprefer.sdk.billing.AppReferBillingException$* { *; }
-keep class com.apprefer.sdk.billing.AppReferCustomerInfo { *; }
-keep class com.apprefer.sdk.billing.AppReferCustomerInfo$* { *; }
-keep class com.apprefer.sdk.billing.AppReferEntitlementInfo { *; }
-keep class com.apprefer.sdk.billing.EntitlementInfos { *; }
-keep enum com.apprefer.sdk.billing.Billing* { *; }

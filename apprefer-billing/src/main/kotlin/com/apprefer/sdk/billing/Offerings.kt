package com.apprefer.sdk.billing

import com.android.billingclient.api.ProductDetails
import org.json.JSONObject

enum class PackageType { WEEKLY, MONTHLY, TWO_MONTH, THREE_MONTH, SIX_MONTH, ANNUAL, LIFETIME, CUSTOM, UNKNOWN;
    companion object {
        fun from(identifier: String, raw: String? = null): PackageType {
            val name = if (identifier.startsWith("\$ar_") || identifier.startsWith("\$rc_")) identifier.substring(4) else raw
            return when (name) {
                "weekly" -> WEEKLY; "monthly" -> MONTHLY; "two_month", "twoMonth" -> TWO_MONTH
                "three_month", "threeMonth" -> THREE_MONTH; "six_month", "sixMonth" -> SIX_MONTH
                "annual" -> ANNUAL; "lifetime" -> LIFETIME; else -> CUSTOM
            }
        }
    }
}
data class OfferingContext(val targetingRuleId: String? = null, val experimentId: String? = null, val variantId: String? = null)
data class PresentedOfferingContext(val offeringId: String, val packageId: String, val placementId: String? = null, val targetingRuleId: String? = null, val experimentId: String? = null, val variantId: String? = null) {
    internal fun json(): JSONObject = JSONObject().put("offeringId", offeringId).put("packageId", packageId)
        .put("placementId", placementId).put("targetingRuleId", targetingRuleId).put("experimentId", experimentId).put("variantId", variantId)
    companion object {
        internal fun parse(o: JSONObject) = PresentedOfferingContext(o.getString("offeringId"), o.getString("packageId"), o.opt("placementId") as? String, o.opt("targetingRuleId") as? String, o.opt("experimentId") as? String, o.opt("variantId") as? String)
    }
}
data class AppReferPackage(val identifier: String, val packageType: PackageType, val storeProduct: ProductDetails, val offeringIdentifier: String, val presentedOfferingContext: PresentedOfferingContext, val offerToken: String? = null, val basePlanId: String? = null)
data class AppReferOffering(val identifier: String, val displayName: String, val metadata: Map<String, Any?>, val availablePackages: List<AppReferPackage>) {
    fun `package`(identifier: String) = availablePackages.firstOrNull { it.identifier == identifier }
    val weekly get() = availablePackages.firstOrNull { it.packageType == PackageType.WEEKLY }
    val monthly get() = availablePackages.firstOrNull { it.packageType == PackageType.MONTHLY }
    val twoMonth get() = availablePackages.firstOrNull { it.packageType == PackageType.TWO_MONTH }
    val threeMonth get() = availablePackages.firstOrNull { it.packageType == PackageType.THREE_MONTH }
    val sixMonth get() = availablePackages.firstOrNull { it.packageType == PackageType.SIX_MONTH }
    val annual get() = availablePackages.firstOrNull { it.packageType == PackageType.ANNUAL }
    val lifetime get() = availablePackages.firstOrNull { it.packageType == PackageType.LIFETIME }
    internal fun forPlacement(id: String) = copy(availablePackages = availablePackages.map { it.copy(presentedOfferingContext = it.presentedOfferingContext.copy(placementId = id)) })
}
data class AppReferOfferings(val current: AppReferOffering?, val all: Map<String, AppReferOffering>, val context: OfferingContext, private val placements: Map<String, String?>) {
    fun currentOffering(forPlacement: String): AppReferOffering? {
        if (placements.containsKey(forPlacement) && placements[forPlacement] == null) return null
        return (if (placements.containsKey(forPlacement)) all[placements[forPlacement]] else current)?.forPlacement(forPlacement)
    }
}
typealias Offerings = AppReferOfferings
typealias Offering = AppReferOffering
typealias Package = AppReferPackage

internal object OfferingsJson {
    fun validate(raw: String): JSONObject = try { JSONObject(raw).also { it.getJSONArray("offerings") } }
        catch (_: Exception) { throw AppReferBillingException.InvalidResponse() }
    suspend fun resolve(raw: String, products: suspend (List<String>) -> List<ProductDetails>): AppReferOfferings {
        val root = validate(raw)
        val offerings = root.getJSONArray("offerings")
        val ids = linkedSetOf<String>()
        for (i in 0 until offerings.length()) {
            val packages = offerings.getJSONObject(i).optJSONArray("packages") ?: continue
            for (j in 0 until packages.length()) (packages.getJSONObject(j).opt("productId") as? String)?.takeIf { it.isNotEmpty() }?.let { ids.add(it.substringBefore(':')) }
        }
        val loaded = try { products(ids.toList()) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }.associateBy { it.productId }
        val c = root.optJSONObject("context") ?: JSONObject()
        val context = OfferingContext(c.opt("targetingRuleId") as? String, c.opt("experimentId") as? String, c.opt("variantId") as? String)
        val all = linkedMapOf<String, AppReferOffering>()
        for (i in 0 until offerings.length()) {
            val o = offerings.getJSONObject(i)
            val id = o.getString("id")
            val packages = mutableListOf<AppReferPackage>()
            val ps = o.optJSONArray("packages")
            for (j in 0 until (ps?.length() ?: 0)) {
                val p = ps!!.getJSONObject(j)
                val productId = p.opt("productId") as? String ?: continue
                val product = loaded[productId.substringBefore(':')] ?: continue
                val type = PackageType.from(p.getString("id"), p.opt("packageType") as? String)
                val base = productId.substringAfter(':', "").takeIf { it.isNotEmpty() }
                val offers = product.subscriptionOfferDetails.orEmpty()
                val period = when(type) { PackageType.WEEKLY -> "P1W"; PackageType.MONTHLY -> "P1M"; PackageType.TWO_MONTH -> "P2M"; PackageType.THREE_MONTH -> "P3M"; PackageType.SIX_MONTH -> "P6M"; PackageType.ANNUAL -> "P1Y"; else -> null }
                val offer = if (base != null) offers.firstOrNull { it.basePlanId == base && it.offerId == null } ?: offers.firstOrNull { it.basePlanId == base }
                    else offers.firstOrNull { it.offerId == null && it.pricingPhases.pricingPhaseList.lastOrNull()?.billingPeriod == period } ?: offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()
                if (base != null && offer == null) continue
                packages.add(AppReferPackage(p.getString("id"), type, product, id, PresentedOfferingContext(id, p.getString("id"), targetingRuleId = context.targetingRuleId, experimentId = context.experimentId, variantId = context.variantId), offer?.offerToken, offer?.basePlanId))
            }
            all[id] = AppReferOffering(id, o.opt("displayName") as? String ?: id, jsonMap(o.optJSONObject("metadata") ?: JSONObject()), packages)
        }
        val placements = root.optJSONObject("placements") ?: JSONObject()
        return AppReferOfferings(all[root.opt("currentOfferingId") as? String], all, context, placements.keys().asSequence().associateWith { placements.opt(it) as? String })
    }
    private fun jsonValue(value: Any?): Any? = when(value) { JSONObject.NULL -> null; is JSONObject -> jsonMap(value); is org.json.JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }; else -> value }
    private fun jsonMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { jsonValue(o.opt(it)) }
}

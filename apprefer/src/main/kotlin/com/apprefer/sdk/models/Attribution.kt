package com.apprefer.sdk.models

data class Attribution(
    val network: String,
    val campaign: String? = null,
    val matchType: String,
    val attributionId: String? = null,
    val campaignId: String? = null,
    val campaignName: String? = null,
    val adGroupId: String? = null,
    val adId: String? = null,
    val keyword: String? = null,
    val fbclid: String? = null,
    val gclid: String? = null,
    val ttclid: String? = null,
    val queryParams: Map<String, String> = emptyMap(),
    val customData: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun organic(): Attribution = Attribution(
            network = "organic",
            matchType = "organic",
        )
    }
}

package com.apprefer.sdk.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/** Check at collection time as well as engine publication: StateFlow replays old values. */
internal fun environmentSnapshots(source: Flow<AppReferCustomerInfo>, environment: () -> BillingEnvironment?): Flow<AppReferCustomerInfo> =
    source.filter { info -> environment()?.let { info.environment == it } == true }

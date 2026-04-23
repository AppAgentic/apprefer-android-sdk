package com.apprefer.sdk

interface AppReferCallback<T> {
    fun onResult(result: T)
    fun onError(error: Throwable) {}
}

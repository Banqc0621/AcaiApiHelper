package com.ban.acai.model

import java.net.CookieManager
import java.net.CookieStore

/**
 * Cookie存储管理器（原始Kotlin版本）
 */
class CookieStoreManager {
    private val cookieManager = CookieManager()
    fun getCookieStore(): CookieStore = cookieManager.cookieStore
    fun clear() = cookieManager.cookieStore.removeAll()
}

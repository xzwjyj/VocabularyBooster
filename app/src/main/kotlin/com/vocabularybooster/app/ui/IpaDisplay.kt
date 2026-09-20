package com.vocabularybooster.app.ui

/**
 * FR-22 双音标显示：`US /…/  UK /…/`——有则双显、无则单显、全缺 → null（不渲染空行）。
 * 斜杠兼容：库内两种存法并存（种子数据带斜杠、词典导入不带），已带斜杠则原样。
 */
internal fun formatIpaLine(ipaAm: String?, ipaBr: String?): String? {
    val us = ipaAm?.trim()?.takeIf { it.isNotBlank() }?.let(::wrapIpa)
    val uk = ipaBr?.trim()?.takeIf { it.isNotBlank() }?.let(::wrapIpa)
    return when {
        us != null && uk != null -> "US $us  UK $uk"
        us != null -> "US $us"
        uk != null -> "UK $uk"
        else -> null
    }
}

private fun wrapIpa(ipa: String): String =
    if (ipa.startsWith("/") && ipa.endsWith("/") && ipa.length > 1) ipa else "/$ipa/"

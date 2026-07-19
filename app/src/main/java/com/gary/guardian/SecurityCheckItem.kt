package com.gary.guardian

import android.content.Context

enum class CheckStatus { SAFE, WARNING, INFO }

data class SecurityCheckResult(val status: CheckStatus, val detail: String)

data class SecurityCheckItem(
    val title: String,
    val explanation: String,
    val fixSettingsAction: String?,
    val check: (Context) -> SecurityCheckResult
)

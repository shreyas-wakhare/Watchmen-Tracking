package com.watchmen.tracker

data class Announcement(
    val title: String,
    val message: String,
    val priority: String = "NORMAL", // NORMAL | HIGH | CRITICAL
    val tts: Boolean = true,
    val vibrate: Boolean = false,
    val raise_alert: Boolean = false,
    val language: String = "en"
)

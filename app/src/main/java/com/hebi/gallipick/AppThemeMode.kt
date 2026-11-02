package com.hebi.gallipick

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

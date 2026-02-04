package com.xingheyuzhuan.shiguangschedule.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.util.Locale

object LocaleHelper {

    /**
     * 检查是否应该使用系统语言设置 (Android 13+)
     */
    val useSystemLanguageSettings: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 启动系统应用语言设置 (Android 13+)
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun launchSystemLanguageSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 如果系统设置不可用，静默失败
            e.printStackTrace()
        }
    }

    /**
     * 获取当前应用语言设置
     */
    fun getCurrentAppLocale(context: Context): Locale? {
        return if (useSystemLanguageSettings) {
            // Android 13+ - 从系统应用语言设置获取
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? android.app.LocaleManager
                    val locales = localeManager?.applicationLocales
                    if (locales != null && !locales.isEmpty) {
                        locales.get(0)
                    } else {
                        null // 系统默认
                    }
                } catch (e: Exception) {
                    null // 系统默认
                }
            } else {
                null // 系统默认
            }
        } else {
            // Android 13 以下 - 从 SharedPreferences 获取
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val localeTag = prefs.getString("app_locale", "system") ?: "system"
            if (localeTag == "system") {
                null // 系统默认
            } else {
                parseLocaleTag(localeTag)
            }
        }
    }

    /**
     * 应用保存的语言设置到 Context (Android 13 以下)
     */
    fun applyLanguage(context: Context): Context {
        // Android 13+ 由系统处理
        if (useSystemLanguageSettings) {
            return context
        }

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val localeTag = prefs.getString("app_locale", "system") ?: "system"

        return if (localeTag == "system") {
            context
        } else {
            val locale = parseLocaleTag(localeTag)
            setLocale(context, locale)
        }
    }

    /**
     * 设置 Context 的语言环境 (Android 13 以下)
     */
    private fun setLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    /**
     * 解析语言标签为 Locale 对象
     */
    private fun parseLocaleTag(tag: String): Locale {
        return try {
            if (tag.contains("_")) {
                val parts = tag.split("_")
                Locale.Builder()
                    .setLanguage(parts[0])
                    .setRegion(parts.getOrNull(1) ?: "")
                    .build()
            } else {
                Locale.Builder()
                    .setLanguage(tag)
                    .build()
            }
        } catch (e: Exception) {
            Locale.getDefault()
        }
    }

    /**
     * 刷新当前 Activity (用于语言切换后重启)
     */
    fun refreshActivity(context: Context) {
        if (context is Activity) {
            context.recreate()
        }
    }
}

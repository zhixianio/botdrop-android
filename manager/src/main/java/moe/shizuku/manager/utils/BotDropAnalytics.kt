package moe.shizuku.manager.utils

import android.content.Context
import android.util.Log

object BotDropAnalytics {

    private const val TAG = "BotDropAnalytics"
    private const val ANALYTICS_MANAGER_CLASS = "com.termux.app.AnalyticsManager"

    fun logScreen(context: Context, screenName: String, screenClass: String) {
        invoke(
            "logScreen",
            arrayOf(Context::class.java, String::class.java, String::class.java),
            arrayOf(context.applicationContext, screenName, screenClass)
        )
    }

    fun logEvent(context: Context, eventName: String) {
        invoke(
            "logEvent",
            arrayOf(Context::class.java, String::class.java),
            arrayOf(context.applicationContext, eventName)
        )
    }

    fun logEvent(context: Context, eventName: String, paramName: String, paramValue: String) {
        invoke(
            "logEvent",
            arrayOf(Context::class.java, String::class.java, String::class.java, String::class.java),
            arrayOf(context.applicationContext, eventName, paramName, paramValue)
        )
    }

    private fun invoke(methodName: String, parameterTypes: Array<Class<*>>, args: Array<Any>) {
        try {
            val clazz = Class.forName(ANALYTICS_MANAGER_CLASS)
            val method = clazz.getMethod(methodName, *parameterTypes)
            method.invoke(null, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to invoke AnalyticsManager.$methodName: ${t.message}")
        }
    }
}

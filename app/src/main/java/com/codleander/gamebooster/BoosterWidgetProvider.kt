package com.codleander.gamebooster

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home screen widget: shows whether a session is running and offers one-tap
 * boost / stop actions without opening the app.
 */
class BoosterWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_STOP) {
            BoosterTileService.stopSession(context)
            refreshAll(context)
        }
    }

    companion object {
        const val ACTION_STOP = "com.codleander.gamebooster.widget.STOP"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = android.content.ComponentName(context, BoosterWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_booster)
            val active = BoosterTileService.isSessionActive(context)
            val runtime = context.getSharedPreferences("booster_runtime", Context.MODE_PRIVATE)
            val label = runtime.getString("target_label", null)

            views.setTextViewText(
                R.id.widgetState,
                when {
                    !active -> context.getString(R.string.widget_sub_idle)
                    label.isNullOrBlank() -> context.getString(R.string.widget_sub_active)
                    else -> label
                }
            )

            val boostIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_QUICK_BOOST
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widgetBoost,
                PendingIntent.getActivity(
                    context,
                    10,
                    boostIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val stopIntent = Intent(context, BoosterWidgetProvider::class.java).setAction(ACTION_STOP)
            views.setOnClickPendingIntent(
                R.id.widgetStop,
                PendingIntent.getBroadcast(
                    context,
                    11,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setViewVisibility(R.id.widgetStop, if (active) android.view.View.VISIBLE else android.view.View.GONE)
            return views
        }
    }
}

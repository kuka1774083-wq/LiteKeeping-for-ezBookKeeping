package com.ezbookkeeping.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.app.AlarmManager;
import android.app.PendingIntent;
import java.util.Calendar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookkeepingWidgetProvider extends AppWidgetProvider {
    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String ACTION_TOGGLE_PERIOD = "com.ezbookkeeping.widget.TOGGLE_PERIOD";
    private static final String PREF_WIDGET = "widget_state";

    @Override public void onEnabled(Context context) { scheduleRefresh(context); }
    @Override public void onDisabled(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) alarm.cancel(refreshIntent(context));
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            manager.updateAppWidget(appWidgetId, createViews(context));
        }
        if (SecureSettings.isConfigured(context)) {
            PendingResult pendingResult = goAsync();
            NETWORK_EXECUTOR.execute(() -> {
                try {
                    ApiClient client = ApiClient.configured(context);
                    Exception last = null;
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try { client.refreshAccountSummary(context); last = null; break; }
                        catch (Exception error) { last = error; try { Thread.sleep(350L * (attempt + 1)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
                    }
                    if (last != null) throw last;
                    saveSpending(context, client.loadRecentTransactions());
                    for (int appWidgetId : appWidgetIds) {
                        RemoteViews views = createViews(context);
                        manager.updateAppWidget(appWidgetId, views);
                    }
                } catch (Exception ignored) {
                    // Keep showing the last encrypted cache while the server is unavailable.
                    for (int appWidgetId : appWidgetIds) {
                        RemoteViews views = createViews(context);
                        manager.updateAppWidget(appWidgetId, views);
                    }
                } finally {
                    pendingResult.finish();
                }
            });
        }
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, BookkeepingWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) {
            manager.updateAppWidget(id, createViews(context));
        }
    }

    private static RemoteViews createViews(Context context) {
        ApiModels.SpendingSummary summary = cachedSpending(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_bookkeeping);
        boolean month = context.getSharedPreferences(PREF_WIDGET, 0).getBoolean("period_month", false);
        views.setTextViewText(R.id.widget_period_label, context.getString(month ? R.string.widget_month_expense : R.string.widget_today_expense));
        views.setInt(R.id.widget_period_label, "setBackgroundResource", month ? R.drawable.widget_period_month : R.drawable.widget_period_today);
        views.setTextViewText(R.id.widget_balance, MoneyFormatter.format(month ? summary.month : summary.today, summary.currency));
        if (SecureSettings.isConfigured(context)) {
            views.setViewVisibility(R.id.widget_income, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_expense, android.view.View.GONE);
        } else {
            views.setViewVisibility(R.id.widget_income, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_expense, android.view.View.GONE);
        }
        views.setOnClickPendingIntent(R.id.widget_root, activityIntent(context, null, 0));
        views.setOnClickPendingIntent(R.id.widget_expense_button, activityIntent(context, "expense", 1));
        views.setOnClickPendingIntent(R.id.widget_income_button, activityIntent(context, "income", 2));
        views.setOnClickPendingIntent(R.id.widget_transfer_button, activityIntent(context, "transfer", 3));
        views.setOnClickPendingIntent(R.id.widget_web_button, webIntent(context));
        views.setOnClickPendingIntent(R.id.widget_period_toggle, toggleIntent(context));
        return views;
    }

    private static void saveSpending(Context context, java.util.List<ApiModels.RemoteTransaction> rows) {
        Calendar now = Calendar.getInstance(); long today = 0, month = 0; String currency = "CNY";
        for (ApiModels.RemoteTransaction row : rows) if (row.type == 3) {
            Calendar t = Calendar.getInstance(); t.setTimeInMillis(row.timeSeconds * 1000L);
            if (!row.currency.isEmpty()) currency = row.currency;
            long expense = Math.abs(row.sourceAmount);
            if (t.get(Calendar.YEAR) == now.get(Calendar.YEAR) && t.get(Calendar.MONTH) == now.get(Calendar.MONTH)) { month += expense; if (t.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)) today += expense; }
        }
        context.getSharedPreferences(PREF_WIDGET, 0).edit().putLong("today", today).putLong("month", month).putString("currency", currency).apply();
    }
    private static ApiModels.SpendingSummary cachedSpending(Context context) { android.content.SharedPreferences p = context.getSharedPreferences(PREF_WIDGET, 0); return new ApiModels.SpendingSummary(p.getLong("today", 0), p.getLong("month", 0), p.getString("currency", "CNY")); }
    private static PendingIntent toggleIntent(Context context) { Intent i = new Intent(context, BookkeepingWidgetProvider.class).setAction(ACTION_TOGGLE_PERIOD); return PendingIntent.getBroadcast(context, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private static PendingIntent refreshIntent(Context context) { Intent i = new Intent(context, BookkeepingWidgetProvider.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE); return PendingIntent.getBroadcast(context, 6, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private static void scheduleRefresh(Context context) { AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE); if (alarm != null) alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000L, 15 * 60000L, refreshIntent(context)); }

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE_PERIOD.equals(intent.getAction())) { android.content.SharedPreferences p = context.getSharedPreferences(PREF_WIDGET, 0); p.edit().putBoolean("period_month", !p.getBoolean("period_month", false)).apply(); refreshAll(context); return; }
        super.onReceive(context, intent);
    }

    private static PendingIntent activityIntent(Context context, String quickAdd, int requestCode) {
        Intent intent = new Intent(context, quickAdd == null ? MainActivity.class : QuickAddActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (quickAdd != null) {
            intent.putExtra(MainActivity.EXTRA_QUICK_ADD, quickAdd);
        }
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent webIntent(Context context) {
        Intent intent = new Intent(context, WebViewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                4,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}

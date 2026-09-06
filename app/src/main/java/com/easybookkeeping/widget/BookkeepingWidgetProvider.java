package com.easybookkeeping.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookkeepingWidgetProvider extends AppWidgetProvider {
    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            manager.updateAppWidget(appWidgetId, createViews(context));
        }
        if (SecureSettings.isConfigured(context)) {
            PendingResult pendingResult = goAsync();
            NETWORK_EXECUTOR.execute(() -> {
                try {
                    ApiClient.configured(context).refreshAccountSummary(context);
                    for (int appWidgetId : appWidgetIds) {
                        manager.updateAppWidget(appWidgetId, createViews(context));
                    }
                } catch (Exception ignored) {
                    // Keep showing the last encrypted cache while the server is unavailable.
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
        ApiModels.AccountSummary summary = ApiClient.cachedAccountSummary(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_bookkeeping);
        views.setTextViewText(R.id.widget_balance, MoneyFormatter.format(summary.balance));
        if (SecureSettings.isConfigured(context)) {
            views.setTextViewText(R.id.widget_income, "已连接服务器");
            views.setTextViewText(R.id.widget_expense, summary.count + " 个账户");
        } else {
            views.setTextViewText(R.id.widget_income, "轻触打开并登录");
            views.setTextViewText(R.id.widget_expense, "");
        }
        views.setOnClickPendingIntent(R.id.widget_root, activityIntent(context, null, 0));
        views.setOnClickPendingIntent(R.id.widget_expense_button, activityIntent(context, "expense", 1));
        views.setOnClickPendingIntent(R.id.widget_income_button, activityIntent(context, "income", 2));
        views.setOnClickPendingIntent(R.id.widget_transfer_button, activityIntent(context, "transfer", 3));
        views.setOnClickPendingIntent(R.id.widget_web_button, webIntent(context));
        return views;
    }

    private static PendingIntent activityIntent(Context context, String quickAdd, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                4,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}

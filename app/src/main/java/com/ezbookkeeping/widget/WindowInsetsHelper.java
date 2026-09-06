package com.ezbookkeeping.widget;

import android.os.Build;
import android.view.View;

/** Applies system-bar insets without requiring a support-library dependency. */
final class WindowInsetsHelper {
    private WindowInsetsHelper() {}

    static void apply(View view, int baseLeft, int baseTop, int baseRight, int baseBottom) {
        view.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars()
                );
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(baseLeft, baseTop + top, baseRight, baseBottom + bottom);
            return insets;
        });
        view.requestApplyInsets();
    }
}

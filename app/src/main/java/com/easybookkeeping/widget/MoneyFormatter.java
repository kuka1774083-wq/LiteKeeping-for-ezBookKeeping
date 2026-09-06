package com.easybookkeeping.widget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

final class MoneyFormatter {
    private MoneyFormatter() {}

    static long parseCents(String value) {
        return new BigDecimal(value.trim())
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }

    static String format(long cents) {
        return format(cents, "CNY");
    }

    static String format(long cents, String currencyCode) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.CHINA);
        if (currencyCode != null && !currencyCode.isEmpty() && !"---".equals(currencyCode)) {
            try {
                formatter.setCurrency(Currency.getInstance(currencyCode));
            } catch (IllegalArgumentException ignored) {
                // Fall back to the default CNY formatter for unknown server codes.
            }
        }
        return formatter.format(BigDecimal.valueOf(cents, 2));
    }
}

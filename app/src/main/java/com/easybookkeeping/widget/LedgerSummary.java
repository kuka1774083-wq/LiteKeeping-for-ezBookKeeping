package com.easybookkeeping.widget;

import java.util.Calendar;
import java.util.List;

final class LedgerSummary {
    final long incomeCents;
    final long expenseCents;

    private LedgerSummary(long incomeCents, long expenseCents) {
        this.incomeCents = incomeCents;
        this.expenseCents = expenseCents;
    }

    long balanceCents() {
        return incomeCents - expenseCents;
    }

    static LedgerSummary currentMonth(List<Transaction> entries) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MONTH, 1);

        long income = 0;
        long expense = 0;
        for (Transaction entry : entries) {
            if (entry.createdAt < start.getTimeInMillis() || entry.createdAt >= end.getTimeInMillis()) {
                continue;
            }
            if (entry.income) {
                income += entry.amountCents;
            } else {
                expense += entry.amountCents;
            }
        }
        return new LedgerSummary(income, expense);
    }
}


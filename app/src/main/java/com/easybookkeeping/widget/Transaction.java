package com.easybookkeeping.widget;

final class Transaction {
    final long id;
    final long createdAt;
    final long amountCents;
    final boolean income;
    final String note;

    Transaction(long id, long createdAt, long amountCents, boolean income, String note) {
        this.id = id;
        this.createdAt = createdAt;
        this.amountCents = amountCents;
        this.income = income;
        this.note = note;
    }
}


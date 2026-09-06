package com.ezbookkeeping.widget;

import java.util.List;

final class ApiModels {
    private ApiModels() {}

    static final class Account {
        final String id;
        final String name;
        final String currency;
        final long balance;
        final String parentId;

        Account(
                String id,
                String name,
                String currency,
                long balance,
                String parentId
        ) {
            this.id = id;
            this.name = name;
            this.currency = currency;
            this.balance = balance;
            this.parentId = parentId;
        }

        @Override
        public String toString() {
            return name + " · " + MoneyFormatter.format(balance, currency);
        }
    }

    static final class AccountGroup {
        final String id;
        final String name;
        final List<Account> children;

        AccountGroup(String id, String name, List<Account> children) {
            this.id = id;
            this.name = name;
            this.children = children;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class Category {
        final String id;
        final String name;
        final List<Category> children;

        Category(String id, String name, List<Category> children) {
            this.id = id;
            this.name = name;
            this.children = children;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class Tag {
        final String id;
        final String name;
        final String groupId;

        Tag(String id, String name, String groupId) {
            this.id = id;
            this.name = name;
            this.groupId = groupId;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class ReferenceData {
        final List<AccountGroup> accountGroups;
        final List<Category> incomeCategories;
        final List<Category> expenseCategories;
        final List<Category> transferCategories;
        final List<Tag> tags;

        ReferenceData(
                List<AccountGroup> accountGroups,
                List<Category> incomeCategories,
                List<Category> expenseCategories,
                List<Category> transferCategories,
                List<Tag> tags
        ) {
            this.accountGroups = accountGroups;
            this.incomeCategories = incomeCategories;
            this.expenseCategories = expenseCategories;
            this.transferCategories = transferCategories;
            this.tags = tags;
        }
    }

    static final class AccountSummary {
        final long balance;
        final int count;
        final String currency;

        AccountSummary(long balance, int count, String currency) {
            this.balance = balance;
            this.count = count;
            this.currency = currency;
        }
    }

    static final class GeoLocation {
        final double latitude;
        final double longitude;

        GeoLocation(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        String displayValue() {
            return String.format(java.util.Locale.US, "%.6f, %.6f", latitude, longitude);
        }
    }

    /** A transaction returned by the server list API.
     *
     * The app deliberately keeps this as an in-memory presentation model. It
     * is never persisted locally; the server remains the source of truth.
     */
    static final class RemoteTransaction {
        final String id;
        final int type;
        final long timeSeconds;
        final long sourceAmount;
        final long destinationAmount;
        final String currency;
        final String categoryName;
        final String sourceAccountName;
        final String destinationAccountName;
        final String comment;
        final List<String> tagNames;

        RemoteTransaction(
                String id,
                int type,
                long timeSeconds,
                long sourceAmount,
                long destinationAmount,
                String currency,
                String categoryName,
                String sourceAccountName,
                String destinationAccountName,
                String comment,
                List<String> tagNames
        ) {
            this.id = id;
            this.type = type;
            this.timeSeconds = timeSeconds;
            this.sourceAmount = sourceAmount;
            this.destinationAmount = destinationAmount;
            this.currency = currency;
            this.categoryName = categoryName;
            this.sourceAccountName = sourceAccountName;
            this.destinationAccountName = destinationAccountName;
            this.comment = comment;
            this.tagNames = tagNames;
        }
    }
}

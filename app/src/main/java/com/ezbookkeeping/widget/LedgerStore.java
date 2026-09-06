package com.ezbookkeeping.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

final class LedgerStore {
    private static final String PREFS = "ledger";
    private static final String KEY_ENTRIES = "entries";

    private LedgerStore() {}

    static List<Transaction> load(Context context) {
        String raw = preferences(context).getString(KEY_ENTRIES, "[]");
        List<Transaction> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new Transaction(
                        item.getLong("id"),
                        item.getLong("createdAt"),
                        item.getLong("amountCents"),
                        item.getBoolean("income"),
                        item.optString("note", "")
                ));
            }
        } catch (JSONException ignored) {
            // Keep the app usable if an older or partially written payload is encountered.
        }
        Collections.sort(result, new Comparator<Transaction>() {
            @Override
            public int compare(Transaction left, Transaction right) {
                return Long.compare(right.createdAt, left.createdAt);
            }
        });
        return result;
    }

    static void add(Context context, long amountCents, boolean income, String note) {
        List<Transaction> entries = load(context);
        long now = System.currentTimeMillis();
        entries.add(0, new Transaction(now, now, amountCents, income, note));
        save(context, entries);
    }

    static void delete(Context context, long id) {
        List<Transaction> entries = load(context);
        Iterator<Transaction> iterator = entries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id == id) {
                iterator.remove();
                break;
            }
        }
        save(context, entries);
    }

    private static void save(Context context, List<Transaction> entries) {
        JSONArray array = new JSONArray();
        for (Transaction entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id);
                item.put("createdAt", entry.createdAt);
                item.put("amountCents", entry.amountCents);
                item.put("income", entry.income);
                item.put("note", entry.note);
                array.put(item);
            } catch (JSONException ignored) {
                // Values are primitive and should always serialize.
            }
        }
        preferences(context).edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

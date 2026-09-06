package com.easybookkeeping.widget;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

final class ApiClient {
    private final String origin;
    private final String token;

    private ApiClient(String origin, String token) {
        this.origin = origin;
        this.token = token;
    }

    static ApiClient configured(Context context) {
        return new ApiClient(SecureSettings.origin(context), SecureSettings.token(context));
    }

    static String login(String serverOrigin, String loginName, String password) throws Exception {
        ApiClient client = new ApiClient(SecureSettings.normalizeOrigin(serverOrigin), "");
        JSONObject body = new JSONObject();
        body.put("loginName", loginName.trim());
        body.put("password", password);
        JSONObject result = client.request("POST", "/authorize.json", body).getJSONObject("result");
        if (result.optBoolean("need2FA", false)) {
            throw new ApiException("该账户需要两步验证，小组件暂不支持登录");
        }
        String token = result.optString("token", "");
        if (token.isEmpty()) {
            throw new ApiException("登录响应中没有 token");
        }
        return token;
    }

    ApiModels.ReferenceData loadReferenceData() throws Exception {
        JSONObject accountsResponse = request(
                "GET",
                "/v1/accounts/list.json?visible_only=true",
                null
        );
        JSONArray rawAccounts = accountsResponse.getJSONArray("result");
        List<ApiModels.AccountGroup> accountGroups = parseAccountGroups(rawAccounts);

        JSONObject categoryResponse = request(
                "GET",
                "/v1/transaction/categories/list.json",
                null
        );
        JSONObject grouped = categoryResponse.getJSONObject("result");
        List<ApiModels.Category> income = parseCategories(grouped.optJSONArray("1"));
        List<ApiModels.Category> expense = parseCategories(grouped.optJSONArray("2"));
        List<ApiModels.Category> transfer = parseCategories(grouped.optJSONArray("3"));

        JSONObject tagsResponse = request(
                "GET",
                "/v1/transaction/tags/list.json?visible_only=true",
                null
        );
        List<ApiModels.Tag> tags = parseTags(tagsResponse.getJSONArray("result"));
        return new ApiModels.ReferenceData(accountGroups, income, expense, transfer, tags);
    }

    ApiModels.AccountSummary refreshAccountSummary(Context context) throws Exception {
        JSONObject response = request("GET", "/v1/accounts/list.json?visible_only=true", null);
        JSONArray raw = response.getJSONArray("result");
        ApiModels.AccountSummary summary = summarize(parseAccountGroups(raw));
        SecureSettings.saveAccountCache(context, raw.toString());
        return summary;
    }

    List<ApiModels.RemoteTransaction> loadRecentTransactions() throws Exception {
        return loadRecentTransactions(null);
    }

    List<ApiModels.RemoteTransaction> loadRecentTransactions(ApiModels.ReferenceData reference) throws Exception {
        JSONObject response = request(
                "GET",
                "/v1/transactions/list.json?max_time=0&min_time=0&type=0&category_ids="
                        + "&account_ids=&tag_filter=&amount_filter=&keyword=&match_mode=0"
                        + "&must_have_pictures=false&count=15&page=1&with_count=true"
                        + "&with_pictures=false&trim_account=true&trim_category=false&trim_tag=true",
                null
        );
        Object rawResult = response.opt("result");
        JSONArray items = null;
        if (rawResult instanceof JSONArray) {
            items = (JSONArray) rawResult;
        } else if (rawResult instanceof JSONObject) {
            JSONObject result = (JSONObject) rawResult;
            items = firstArray(result, "items", "transactions", "list", "data");
        }
        List<ApiModels.RemoteTransaction> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (int i = 0; i < items.length(); i++) {
            Object item = items.opt(i);
            if (item instanceof JSONObject) {
                result.add(parseRemoteTransaction((JSONObject) item, reference));
            }
        }
        return result;
    }

    static ApiModels.AccountSummary cachedAccountSummary(Context context) {
        try {
            return summarize(parseAccountGroups(new JSONArray(SecureSettings.accountCache(context))));
        } catch (Exception ignored) {
            return new ApiModels.AccountSummary(0, 0, "CNY");
        }
    }

    void addTransaction(
            int type,
            String categoryId,
            String sourceAccountId,
            String destinationAccountId,
            long sourceAmount,
            long destinationAmount,
            List<String> tagIds,
            String comment,
            long timeMillis,
            ApiModels.GeoLocation geoLocation
    ) throws Exception {
        TimeZone timeZone = TimeZone.getDefault();
        int utcOffsetMinutes = timeZone.getOffset(timeMillis) / 60_000;

        JSONObject body = new JSONObject();
        body.put("type", type);
        body.put("categoryId", categoryId);
        body.put("time", timeMillis / 1_000);
        body.put("utcOffset", utcOffsetMinutes);
        body.put("sourceAccountId", sourceAccountId);
        body.put("destinationAccountId", destinationAccountId);
        body.put("sourceAmount", sourceAmount);
        body.put("destinationAmount", destinationAmount);
        body.put("hideAmount", false);
        JSONArray tags = new JSONArray();
        for (String tagId : tagIds) {
            tags.put(tagId);
        }
        body.put("tagIds", tags);
        body.put("pictureIds", new JSONArray());
        body.put("comment", comment);
        if (geoLocation != null) {
            JSONObject location = new JSONObject();
            location.put("latitude", geoLocation.latitude);
            location.put("longitude", geoLocation.longitude);
            body.put("geoLocation", location);
        }
        body.put("clientSessionId", UUID.randomUUID().toString());
        request("POST", "/v1/transactions/add.json", body);
    }

    private static JSONArray firstArray(JSONObject object, String... keys) {
        for (String key : keys) {
            JSONArray array = object.optJSONArray(key);
            if (array != null) {
                return array;
            }
        }
        return null;
    }

    private static ApiModels.RemoteTransaction parseRemoteTransaction(JSONObject item, ApiModels.ReferenceData reference) {
        JSONObject category = item.optJSONObject("category");
        JSONObject sourceAccount = item.optJSONObject("sourceAccount");
        JSONObject destinationAccount = item.optJSONObject("destinationAccount");
        String currency = sourceAccount == null
                ? "CNY"
                : sourceAccount.optString("currency", "CNY");
        List<String> tagNames = new ArrayList<>();
        JSONArray tags = item.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                Object rawTag = tags.opt(i);
                if (rawTag instanceof JSONObject) {
                    String name = ((JSONObject) rawTag).optString("name", "");
                    if (!name.isEmpty()) {
                        tagNames.add(name);
                    }
                } else if (rawTag != null && rawTag != JSONObject.NULL) {
                    tagNames.add(String.valueOf(rawTag));
                }
            }
        }
        String parsedCategoryName = categoryName(item, category);
        if (parsedCategoryName.isEmpty() && reference != null) {
            parsedCategoryName = findCategoryName(reference, item.optString("categoryId", ""));
        }
        return new ApiModels.RemoteTransaction(
                item.optString("id", ""),
                item.optInt("type", 0),
                parseLong(item.opt("time")),
                parseMinorAmount(item.opt("sourceAmount")),
                parseMinorAmount(item.opt("destinationAmount")),
                currency,
                parsedCategoryName,
                sourceAccount == null ? "" : sourceAccount.optString("name", ""),
                destinationAccount == null ? "" : destinationAccount.optString("name", ""),
                item.optString("comment", ""),
                tagNames
        );
    }

    private static String findCategoryName(ApiModels.ReferenceData reference, String id) {
        for (List<ApiModels.Category> groups : new List[] { reference.incomeCategories, reference.expenseCategories, reference.transferCategories }) {
            String found = findCategoryName(groups, id);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static String findCategoryName(List<ApiModels.Category> categories, String id) {
        for (ApiModels.Category category : categories) {
            if (category.id.equals(id) && category.children.isEmpty()) return category.name;
            String found = findCategoryName(category.children, id);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static String categoryName(JSONObject item, JSONObject category) {
        String direct = item.optString("categoryName", "").trim();
        if (!direct.isEmpty()) {
            return direct;
        }
        if (category != null) {
            String name = category.optString("name", "").trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return "";
    }

    private static long parseLong(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        if (origin.isEmpty()) {
            throw new ApiException("尚未配置服务器");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + "/api" + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
            TimeZone zone = TimeZone.getDefault();
            connection.setRequestProperty(
                    "X-Timezone-Offset",
                    String.valueOf(zone.getOffset(System.currentTimeMillis()) / 60_000)
            );
            connection.setRequestProperty("X-Timezone-Name", zone.getID());
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();

        JSONObject json;
        try {
            json = new JSONObject(response);
        } catch (JSONException exception) {
            throw new ApiException("服务器返回了无法解析的响应（HTTP " + status + "）");
        }
        if (status < 200 || status >= 300 || !json.optBoolean("success", false)) {
            String message = json.optString("errorMessage", "请求失败（HTTP " + status + "）");
            throw new ApiException(message);
        }
        return json;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static List<ApiModels.AccountGroup> parseAccountGroups(JSONArray array) throws JSONException {
        List<ApiModels.AccountGroup> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            List<ApiModels.Account> children = new ArrayList<>();
            appendAccountLeaves(children, item, "");
            if (!children.isEmpty()) {
                result.add(new ApiModels.AccountGroup(
                        item.getString("id"),
                        item.optString("name", "未命名账户"),
                        children
                ));
            }
        }
        return result;
    }

    private static void appendAccountLeaves(
            List<ApiModels.Account> destination,
            JSONObject item,
            String parentId
    ) throws JSONException {
        String id = item.getString("id");
        String name = item.optString("name", "未命名账户");
        String currency = item.optString("currency", "CNY");
        boolean hidden = item.optBoolean("hidden", false);
        if (!hidden && !"---".equals(currency)) {
            destination.add(new ApiModels.Account(
                    id,
                    name,
                    currency,
                    parseMinorAmount(item.opt("balance")),
                    parentId
            ));
        }
        JSONArray children = item.optJSONArray("subAccounts");
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length(); i++) {
            appendAccountLeaves(destination, children.getJSONObject(i), id);
        }
    }

    private static long parseMinorAmount(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return 0;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            if (value.indexOf('.') >= 0) {
                return new BigDecimal(value)
                        .movePointRight(2)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
            }
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static List<ApiModels.Category> parseCategories(JSONArray array) throws JSONException {
        List<ApiModels.Category> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            result.add(parseCategory(array.getJSONObject(i)));
        }
        return result;
    }

    private static ApiModels.Category parseCategory(JSONObject item) throws JSONException {
        List<ApiModels.Category> children = new ArrayList<>();
        JSONArray rawChildren = item.optJSONArray("subCategories");
        if (rawChildren != null) {
            for (int i = 0; i < rawChildren.length(); i++) {
                children.add(parseCategory(rawChildren.getJSONObject(i)));
            }
        }
        return new ApiModels.Category(
                item.getString("id"),
                item.optString("name", "未命名分类"),
                children
        );
    }

    private static List<ApiModels.Tag> parseTags(JSONArray array) throws JSONException {
        List<ApiModels.Tag> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (!item.optBoolean("hidden", false)) {
                result.add(new ApiModels.Tag(
                        item.getString("id"),
                        item.optString("name", "未命名标签"),
                        item.optString("groupId", "")
                ));
            }
        }
        return result;
    }

    private static ApiModels.AccountSummary summarize(List<ApiModels.AccountGroup> groups) {
        long balance = 0;
        String currency = "CNY";
        int included = 0;
        for (ApiModels.AccountGroup group : groups) {
            for (ApiModels.Account account : group.children) {
                if (included == 0) {
                    currency = account.currency;
                }
                if (currency.equals(account.currency)) {
                    balance += account.balance;
                    included++;
                }
            }
        }
        return new ApiModels.AccountSummary(balance, included, currency);
    }

    static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }
}

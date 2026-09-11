package com.ezbookkeeping.widget;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String EXTRA_QUICK_ADD = "quick_add";
    private static final int TYPE_INCOME = 2;
    private static final int TYPE_EXPENSE = 3;
    private static final int TYPE_TRANSFER = 4;
    private static final int LOCATION_PERMISSION_REQUEST = 4127;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private int spacing;
    private String pendingQuickAdd;
    private int pendingLocationMode = -1;
    private List<ApiModels.RemoteTransaction> recentTransactions = Collections.emptyList();
    private boolean transactionsLoading;
    private float lastTouchY;
    private boolean quickEntryMode;
    private TextView pendingLocationView;
    private ApiModels.GeoLocation currentLocation;
    private Dialog loadingDialog;

    @Override
    protected void onCreate(Bundle state) {
        String quickAdd = getIntent().getStringExtra(EXTRA_QUICK_ADD);
        quickEntryMode = quickAdd != null;
        super.onCreate(state);
        spacing = Math.round(16 * getResources().getDisplayMetrics().density);
        render();
        handleQuickAdd(getIntent().getStringExtra(EXTRA_QUICK_ADD));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SecureSettings.isConfigured(this)) {
            refreshSummary(false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleQuickAdd(intent.getStringExtra(EXTRA_QUICK_ADD));
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void handleQuickAdd(String type) {
        getIntent().removeExtra(EXTRA_QUICK_ADD);
        if (!"income".equals(type) && !"expense".equals(type) && !"transfer".equals(type)) {
            return;
        }
        if (!SecureSettings.isConfigured(this)) {
            pendingQuickAdd = type;
            showLoginDialog();
        } else {
            beginEntry(typeToMode(type));
        }
    }

    private void render() {
        if (quickEntryMode) {
            FrameLayout transparentRoot = new FrameLayout(this);
            transparentRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            content = new LinearLayout(this);
            content.setGravity(Gravity.CENTER);
            transparentRoot.addView(content, new FrameLayout.LayoutParams(-1, -1));
            setContentView(transparentRoot);
            return;
        }
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(spacing, spacing, spacing, spacing);
        scroll.addView(content);
        WindowInsetsHelper.apply(scroll, 0, 0, 0, 0);
        scroll.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastTouchY = event.getY();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                float delta = event.getY() - lastTouchY;
                if (delta < -80 && !scroll.canScrollVertically(1)) {
                    startActivity(new Intent(this, WebViewActivity.class)
                            .putExtra(WebViewActivity.EXTRA_WEB_PATH,
                                    "/mobile#!/transaction/list"));
                }
            }
            return false;
        });

        ApiModels.AccountSummary summary = ApiClient.cachedAccountSummary(this);
        content.addView(text("云端账户总额", 16, 0xff5f6368));
        content.addView(text(MoneyFormatter.format(summary.balance, summary.currency), 36, 0xff202124));
        String count = summary.count == 0
                ? "登录并同步后显示可见账户"
                : summary.count + " 个 " + summary.currency + " 账户";
        TextView countView = text(count, 14, 0xff6b7280);
        countView.setPadding(0, 0, 0, spacing);
        content.addView(countView);
        addConnectionControls();

        LinearLayout addButtons = row();
        addButtons.setPadding(0, spacing, 0, spacing);
        addButtons.addView(button("记转账", () -> beginEntry(TYPE_TRANSFER)), weighted());
        addButtons.addView(button("记收入", () -> beginEntry(TYPE_INCOME)), weighted());
        addButtons.addView(button("记支出", () -> beginEntry(TYPE_EXPENSE)), weighted());
        boolean enabled = SecureSettings.isConfigured(this);
        for (int i = 0; i < addButtons.getChildCount(); i++) {
            addButtons.getChildAt(i).setEnabled(enabled);
        }
        content.addView(addButtons);

        TextView title = text("云端记录", 20, 0xff202124);
        title.setPadding(0, spacing / 2, 0, spacing / 2);
        content.addView(title);
        addRemoteEntries();
        if (transactionsLoading) {
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            spinner.setPadding(0, spacing, 0, spacing);
            content.addView(spinner, 0);
        }
        setContentView(scroll);
    }

    private void addConnectionControls() {
        boolean configured = SecureSettings.isConfigured(this);
        content.addView(text(
                configured ? "已连接 " + SecureSettings.origin(this) : "尚未连接 ezBookkeeping 服务器",
                14,
                configured ? 0xff16865c : 0xffd14f45
        ));
        LinearLayout controls = row();
        controls.addView(button(configured ? "立即同步" : "登录服务器",
                configured ? () -> refreshSummary(true) : this::showLoginDialog), weighted());
        if (configured) {
            controls.addView(button("内嵌网页",
                    () -> startActivity(new Intent(this, WebViewActivity.class))), weighted());
            controls.addView(button("退出登录", () -> new AlertDialog.Builder(this)
                    .setTitle("退出登录")
                    .setMessage("将删除本机加密保存的服务器令牌，服务器数据不会受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("退出", (dialog, which) -> {
                        SecureSettings.clearLogin(this);
                        BookkeepingWidgetProvider.refreshAll(this);
                        render();
                    }).show()), weighted());
        }
        content.addView(controls);
    }

    private void addRemoteEntries() {
        if (!SecureSettings.isConfigured(this)) {
            TextView empty = text("登录并同步后，这里显示服务器交易记录。", 16, 0xff777777);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, spacing * 2, 0, spacing * 2);
            content.addView(empty);
            return;
        }
        if (transactionsLoading) {
            TextView loading = text("正在从服务器读取交易记录…", 16, 0xff777777);
            loading.setGravity(Gravity.CENTER);
            loading.setPadding(0, spacing * 2, 0, spacing * 2);
            content.addView(loading);
            return;
        }
        if (recentTransactions.isEmpty()) {
            TextView empty = text("服务器暂无交易记录。", 16, 0xff777777);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, spacing * 2, 0, spacing * 2);
            content.addView(empty);
            return;
        }
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA);
        for (ApiModels.RemoteTransaction entry : recentTransactions) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, spacing / 2, 0, spacing / 2);
            String type = typeLabel(entry.type);
            String category = entry.categoryName.isEmpty() ? "未分类" : entry.categoryName;
            String account = entry.sourceAccountName;
            if (entry.type == TYPE_TRANSFER && !entry.destinationAccountName.isEmpty()) {
                account = account + " → " + entry.destinationAccountName;
            }
            String note = entry.comment.isEmpty() ? "无备注" : entry.comment;
            StringBuilder detail = new StringBuilder(type).append(" · ").append(category);
            if (!account.isEmpty()) {
                detail.append("\n").append(account);
            }
            detail.append("\n").append(date.format(new Date(entry.timeSeconds * 1_000L)));
            if (!entry.tagNames.isEmpty()) {
                detail.append("  #").append(String.join(" #", entry.tagNames));
            }
            item.addView(text(note + "\n" + detail, 15, 0xff303030), weighted());
            String amountText;
            int amountColor;
            if (entry.type == TYPE_TRANSFER) {
                amountText = "↔ " + MoneyFormatter.format(entry.sourceAmount, entry.currency);
                amountColor = 0xff5f6368;
            } else {
                boolean income = entry.type == TYPE_INCOME;
                amountText = (income ? "+" : "-")
                        + MoneyFormatter.format(entry.sourceAmount, entry.currency);
                amountColor = income ? 0xff16865c : 0xffd14f45;
            }
            TextView amount = text(amountText, 16, amountColor);
            amount.setGravity(Gravity.END);
            item.addView(amount);
            content.addView(item);
        }
    }

    private void showLoginDialog() {
        LinearLayout form = form();
        EditText origin = input("服务器地址，例如 https://example.com", InputType.TYPE_TEXT_VARIATION_URI);
        origin.setText(SecureSettings.origin(this));
        EditText username = input("用户名", InputType.TYPE_CLASS_TEXT);
        EditText password = input("密码（不会保存）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(origin);
        form.addView(username);
        form.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("登录 ezBookkeeping")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("登录", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    final String normalized;
                    try {
                        normalized = SecureSettings.normalizeOrigin(origin.getText().toString());
                    } catch (IllegalArgumentException exception) {
                        toast(exception.getMessage());
                        return;
                    }
                    if (username.getText().toString().trim().isEmpty() || password.length() == 0) {
                        toast("请输入用户名和密码");
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    networkExecutor.execute(() -> {
                        try {
                            String token = ApiClient.login(normalized, username.getText().toString(),
                                    password.getText().toString());
                            SecureSettings.saveLogin(this, normalized, token);
                            runOnUiThread(() -> {
                                dialog.dismiss();
                                render();
                                refreshSummary(false);
                                String quickAdd = pendingQuickAdd;
                                pendingQuickAdd = null;
                                if (quickAdd != null) {
                                    beginEntry(typeToMode(quickAdd));
                                }
                            });
                        } catch (Exception exception) {
                            runOnUiThread(() -> {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                toast("登录失败：" + message(exception));
                            });
                        }
                    });
                }));
        dialog.show();
    }

    private void beginEntry(int mode) {
        if (!SecureSettings.isConfigured(this)) {
            pendingQuickAdd = mode == TYPE_INCOME ? "income"
                    : mode == TYPE_TRANSFER ? "transfer" : "expense";
            showLoginDialog();
            return;
        }
        pendingLocationMode = mode;
        loadEntryForm(mode);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) {
            return;
        }
        int mode = pendingLocationMode;
        pendingLocationMode = -1;
        if (mode >= 0) {
            loadEntryForm(mode);
            return;
        }
        ApiModels.GeoLocation location = captureLocation();
        if (location != null && pendingLocationView != null) {
            currentLocation = location;
            pendingLocationView.setText("当前位置：" + location.displayValue());
            pendingLocationView.setTextColor(0xff16865c);
        }
    }

    private void loadEntryForm(int mode) {
        toast("正在读取账户和分类…");
        showQuickLoading();
        networkExecutor.execute(() -> {
            try {
                ApiModels.ReferenceData data = ApiClient.configured(this).loadReferenceData();
                if (data.accountGroups.isEmpty()) {
                    throw new ApiClient.ApiException("没有可用账户");
                }
                List<ApiModels.Category> categories = categoriesForMode(data, mode);
                if (categories.isEmpty()) {
                    throw new ApiClient.ApiException("没有可用分类");
                }
                if (mode == TYPE_TRANSFER && countAccounts(data.accountGroups) < 2) {
                    throw new ApiClient.ApiException("转账至少需要两个可用账户");
                }
                runOnUiThread(() -> showEntryDialog(mode, data, categories, null));
            } catch (Exception exception) {
                runOnUiThread(() -> toast("读取失败：" + message(exception)));
            }
        });
    }

    private void showQuickLoading() {
        if (!quickEntryMode || content == null) return;
        showLoadingOverlay();
    }

    private void showLoadingOverlay() {
        if (loadingDialog != null && loadingDialog.isShowing()) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(spacing * 2, spacing * 2, spacing * 2, spacing * 2);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(spacing);
        panel.setBackground(background);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.getIndeterminateDrawable().setColorFilter(0xff557eae, android.graphics.PorterDuff.Mode.SRC_IN);
        panel.addView(spinner, new LinearLayout.LayoutParams(spacing * 3, spacing * 3));
        loadingDialog = new Dialog(this);
        loadingDialog.setContentView(panel);
        loadingDialog.setCancelable(false);
        android.view.Window window = loadingDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.55f;
            window.setAttributes(attrs);
        }
        loadingDialog.show();
    }

    private void hideLoadingOverlay() {
        if (loadingDialog != null) {
            loadingDialog.dismiss();
            loadingDialog = null;
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private ApiModels.GeoLocation captureLocation() {
        if (!hasLocationPermission()) {
            return null;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        Location best = null;
        String[] providers = new String[] {
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String provider : providers) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (RuntimeException ignored) {
                // A disabled provider or a device-specific location failure is
                // treated as an unavailable optional location.
            }
        }
        if (best == null || !Double.isFinite(best.getLatitude())
                || !Double.isFinite(best.getLongitude())) {
            return null;
        }
        if (best.getLatitude() < -90 || best.getLatitude() > 90
                || best.getLongitude() < -180 || best.getLongitude() > 180) {
            return null;
        }
        return new ApiModels.GeoLocation(best.getLatitude(), best.getLongitude());
    }

    private void showEntryDialog(
            int mode,
            ApiModels.ReferenceData data,
            List<ApiModels.Category> categories,
            ApiModels.GeoLocation location
    ) {
        LinearLayout form = form();
        Spinner sourceGroup = spinner(data.accountGroups);
        Spinner sourceAccount = spinner(Collections.<ApiModels.Account>emptyList());
        bindAccountSpinners(sourceGroup, sourceAccount, data.accountGroups);
        form.addView(selectorHeaders(
                mode == TYPE_TRANSFER ? "转出账户大类" : "账户大类",
                mode == TYPE_TRANSFER ? "转出子账户" : "子账户"
        ));
        form.addView(selectorRow(sourceGroup, sourceAccount));

        Spinner destinationGroup = null;
        Spinner destinationAccount = null;
        if (mode == TYPE_TRANSFER) {
            destinationGroup = spinner(data.accountGroups);
            destinationAccount = spinner(Collections.<ApiModels.Account>emptyList());
            bindAccountSpinners(destinationGroup, destinationAccount, data.accountGroups);
            form.addView(selectorHeaders("转入账户大类", "转入子账户"));
            form.addView(selectorRow(destinationGroup, destinationAccount));
        }

        Spinner categoryGroup = spinner(categories);
        Spinner category = spinner(Collections.<ApiModels.Category>emptyList());
        bindCategorySpinners(categoryGroup, category, categories);
        form.addView(selectorHeaders(
                mode == TYPE_TRANSFER ? "转账大类" : "分类大类",
                "子分类"
        ));
        form.addView(selectorRow(categoryGroup, category));

        EditText amount = input(
                mode == TYPE_TRANSFER ? "转账金额（转出和转入相同），例如 25.80" : "金额，例如 25.80",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        form.addView(amount);

        Calendar transactionTime = Calendar.getInstance();
        Button timeButton = button(formatTransactionTime(transactionTime.getTimeInMillis()), () -> { });
        timeButton.setOnClickListener(view -> chooseTransactionTime(transactionTime, timeButton));
        form.addView(label("记账时间（可修改）"));
        form.addView(timeButton);

        List<String> selectedTagIds = new ArrayList<>();
        final Button tags = button("标签（未选择）", () -> { });
        tags.setOnClickListener(view -> showTagPicker(data.tags, selectedTagIds, tags));
        if (data.tags.isEmpty()) {
            tags.setText("标签（服务器暂无可用标签）");
            tags.setEnabled(false);
        }
        form.addView(tags);
        EditText note = input("备注（可选）", InputType.TYPE_CLASS_TEXT);
        form.addView(note);
        TextView locationView = text(
                location == null
                        ? "当前位置：未获取（提交时不会上传位置）"
                        : "当前位置：" + location.displayValue(),
                13,
                location == null ? 0xff8a6d3b : 0xff16865c
        );
        locationView.setPadding(0, spacing / 2, 0, spacing);
        form.addView(locationView);
        pendingLocationView = locationView;

        final Spinner finalDestinationGroup = destinationGroup;
        final Spinner finalDestinationAccount = destinationAccount;
        final Calendar finalTransactionTime = transactionTime;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleForMode(mode))
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("提交", null)
                .create();
        dialog.setOnCancelListener(ignored -> {
            if (quickEntryMode) {
                dialog.dismiss();
                finishAndRemoveTask();
            }
        });
        dialog.setOnDismissListener(ignored -> {
            if (quickEntryMode && !isFinishing()) finishAndRemoveTask();
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        ApiModels.Account source = selectedAccount(sourceAccount);
                        ApiModels.Category selectedCategory = selectedCategory(category);
                        long sourceAmount = MoneyFormatter.parseCents(amount.getText().toString());
                        if (sourceAmount <= 0) {
                            throw new IllegalArgumentException("请输入有效的正数金额");
                        }
                        String destinationId = "0";
                        long destinationValue = 0;
                        if (mode == TYPE_TRANSFER) {
                            ApiModels.Account destination = selectedAccount(finalDestinationAccount);
                            if (source.id.equals(destination.id)) {
                                throw new IllegalArgumentException("转出和转入账户不能相同");
                            }
                            destinationId = destination.id;
                            // The server requires both sides of an internal
                            // transfer to match. There is intentionally one
                            // amount field in the UI and it is sent twice.
                            destinationValue = sourceAmount;
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        confirmSubmit(dialog, mode, source, destinationId, destinationValue,
                                selectedCategory, sourceAmount, selectedTagIds,
                                note.getText().toString().trim(),
                                finalTransactionTime.getTimeInMillis(), currentLocation != null ? currentLocation : captureLocation());
                    } catch (IllegalArgumentException exception) {
                        toast(exception.getMessage());
                    }
                }));
        dialog.show();
        if (quickEntryMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasLocationPermission()) {
            pendingLocationMode = -1;
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_PERMISSION_REQUEST);
        }
        ApiModels.GeoLocation initialLocation = captureLocation();
        if (initialLocation != null && pendingLocationView != null) {
            currentLocation = initialLocation;
            pendingLocationView.setText("当前位置：" + initialLocation.displayValue());
            pendingLocationView.setTextColor(0xff16865c);
        }
        amount.requestFocus();
    }

    private void chooseTransactionTime(Calendar target, Button button) {
        DatePickerDialog dateDialog = new DatePickerDialog(
                this,
                (DatePicker view, int year, int month, int day) -> {
                    target.set(Calendar.YEAR, year);
                    target.set(Calendar.MONTH, month);
                    target.set(Calendar.DAY_OF_MONTH, day);
                    new TimePickerDialog(
                            this,
                            (TimePicker timeView, int hour, int minute) -> {
                                target.set(Calendar.HOUR_OF_DAY, hour);
                                target.set(Calendar.MINUTE, minute);
                                target.set(Calendar.SECOND, 0);
                                target.set(Calendar.MILLISECOND, 0);
                                button.setText(formatTransactionTime(target.getTimeInMillis()));
                            },
                            target.get(Calendar.HOUR_OF_DAY),
                            target.get(Calendar.MINUTE),
                            android.text.format.DateFormat.is24HourFormat(this)
                    ).show();
                },
                target.get(Calendar.YEAR),
                target.get(Calendar.MONTH),
                target.get(Calendar.DAY_OF_MONTH)
        );
        dateDialog.show();
    }

    private String formatTransactionTime(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(timeMillis));
    }

    private void confirmSubmit(
            AlertDialog formDialog,
            int mode,
            ApiModels.Account source,
            String destinationId,
            long destinationAmount,
            ApiModels.Category category,
            long sourceAmount,
            List<String> tagIds,
            String comment,
            long timeMillis,
            ApiModels.GeoLocation location
    ) {
        if (location != null && !Double.isNaN(location.latitude)) {
            submitEntry(formDialog, mode, source, destinationId, destinationAmount,
                    category, sourceAmount, tagIds, comment, timeMillis, location);
            return;
        }
        if (location == null) {
            ApiModels.GeoLocation current = captureLocation();
            if (current != null) {
                confirmSubmit(formDialog, mode, source, destinationId, destinationAmount, category, sourceAmount, tagIds, comment, timeMillis, current);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("无法获取当前位置")
                    .setMessage("获取位置失败，是否仍要提交？")
                    .setNegativeButton("取消", (d, w) -> formDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                    .setNeutralButton("重试获取位置", (d, w) -> confirmSubmit(formDialog, mode, source, destinationId, destinationAmount, category, sourceAmount, tagIds, comment, timeMillis, null))
                    .setPositiveButton("继续提交", (d, w) -> confirmSubmit(formDialog, mode, source, destinationId, destinationAmount, category, sourceAmount, tagIds, comment, timeMillis, new ApiModels.GeoLocation(Double.NaN, Double.NaN)))
                    .show();
            return;
        }
        boolean noLocation = location == null || Double.isNaN(location.latitude);
        String locationText = noLocation
                ? "不上传位置"
                : "上传位置 " + location.displayValue();
        new AlertDialog.Builder(this)
                .setTitle("确认提交")
                .setMessage(titleForMode(mode) + "\n"
                        + "金额：" + MoneyFormatter.format(sourceAmount, source.currency)
                        + "\n时间：" + formatTransactionTime(timeMillis)
                        + "\n" + locationText
                        + "\n\n确认后将直接上传服务器，不在本机保存提交记录。")
                .setNegativeButton("返回修改", (dialog, which) ->
                        formDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                .setPositiveButton("确认上传", (dialog, which) -> submitEntry(
                        formDialog, mode, source, destinationId, destinationAmount,
                        category, sourceAmount, tagIds, comment, timeMillis, noLocation ? null : location
                ))
                .setOnCancelListener(dialog ->
                        formDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                .show();
    }

    private void submitEntry(
            AlertDialog dialog,
            int mode,
            ApiModels.Account source,
            String destinationId,
            long destinationAmount,
            ApiModels.Category category,
            long sourceAmount,
            List<String> tagIds,
            String comment,
            long timeMillis,
            ApiModels.GeoLocation location
    ) {
        networkExecutor.execute(() -> {
            try {
                ApiClient client = ApiClient.configured(this);
                client.addTransaction(
                        mode,
                        category.id,
                        source.id,
                        destinationId,
                        sourceAmount,
                        destinationAmount,
                        new ArrayList<>(tagIds),
                        comment,
                        timeMillis,
                        location
                );
                client.refreshAccountSummary(this);
                List<ApiModels.RemoteTransaction> remote = client.loadRecentTransactions();
                BookkeepingWidgetProvider.saveSpending(this, remote);
                BookkeepingWidgetProvider.refreshAll(this);
                runOnUiThread(() -> {
                    hideLoadingOverlay();
                    recentTransactions = remote;
                    dialog.dismiss();
                    if (quickEntryMode) finishAndRemoveTask();
                    render();
                    toast(mode == TYPE_TRANSFER ? "转账已上传服务器" : "账单已上传服务器");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    hideLoadingOverlay();
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    toast("提交失败：" + message(exception));
                });
            }
        });
    }

    private void refreshSummary(boolean announce) {
        if (!SecureSettings.isConfigured(this)) {
            return;
        }
        transactionsLoading = true;
        showLoadingOverlay();
        if (!announce && recentTransactions.isEmpty()) {
            toast("正在同步云端数据，请稍候…");
        }
        if (announce) {
            render();
        }
        networkExecutor.execute(() -> {
            try {
                ApiClient client = ApiClient.configured(this);
                client.refreshAccountSummary(this);
                List<ApiModels.RemoteTransaction> remote = client.loadRecentTransactions();
                BookkeepingWidgetProvider.refreshAll(this);
                runOnUiThread(() -> {
                    hideLoadingOverlay();
                    recentTransactions = remote;
                    transactionsLoading = false;
                    render();
                    if (announce) {
                        toast("同步完成");
                    }
                });
            } catch (Exception exception) {
                transactionsLoading = false;
                runOnUiThread(() -> {
                    hideLoadingOverlay();
                    render();
                    if (announce) {
                        toast("同步失败：" + message(exception));
                    }
                });
            }
        });
    }

    private String typeLabel(int type) {
        if (type == TYPE_INCOME) {
            return "收入";
        }
        if (type == TYPE_TRANSFER) {
            return "转账";
        }
        if (type == TYPE_EXPENSE) {
            return "支出";
        }
        return "交易";
    }

    private void bindAccountSpinners(
            Spinner groupSpinner,
            Spinner accountSpinner,
            List<ApiModels.AccountGroup> groups
    ) {
        groupSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                ApiModels.AccountGroup group = groups.get(position);
                accountSpinner.setAdapter(spinnerAdapter(group.children));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                accountSpinner.setAdapter(spinnerAdapter(Collections.<ApiModels.Account>emptyList()));
            }
        });
        groupSpinner.setSelection(0);
    }

    private void bindCategorySpinners(
            Spinner groupSpinner,
            Spinner categorySpinner,
            List<ApiModels.Category> groups
    ) {
        groupSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                List<ApiModels.Category> choices = categoryOptions(groups.get(position));
                categorySpinner.setAdapter(spinnerAdapter(choices));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                categorySpinner.setAdapter(spinnerAdapter(Collections.<ApiModels.Category>emptyList()));
            }
        });
        groupSpinner.setSelection(0);
    }

    private List<ApiModels.Category> categoryOptions(ApiModels.Category group) {
        if (group.children.isEmpty()) {
            return Collections.singletonList(group);
        }
        List<ApiModels.Category> result = new ArrayList<>();
        appendCategoryOptions(result, group.children, "");
        return result;
    }

    private void appendCategoryOptions(
            List<ApiModels.Category> destination,
            List<ApiModels.Category> categories,
            String prefix
    ) {
        for (ApiModels.Category category : categories) {
            String name = prefix.isEmpty() ? category.name : prefix + " / " + category.name;
            if (category.children.isEmpty()) {
                destination.add(new ApiModels.Category(category.id, name, Collections.emptyList()));
            } else {
                appendCategoryOptions(destination, category.children, name);
            }
        }
    }

    private void showTagPicker(
            List<ApiModels.Tag> tags,
            List<String> selectedTagIds,
            Button button
    ) {
        String[] names = new String[tags.size()];
        boolean[] checked = new boolean[tags.size()];
        List<String> draft = new ArrayList<>(selectedTagIds);
        for (int i = 0; i < tags.size(); i++) {
            names[i] = tags.get(i).name;
            checked[i] = draft.contains(tags.get(i).id);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择标签（可多选）")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    String id = tags.get(which).id;
                    if (isChecked && !draft.contains(id)) {
                        draft.add(id);
                    } else if (!isChecked) {
                        draft.remove(id);
                    }
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("完成", (dialog, which) -> {
                    selectedTagIds.clear();
                    selectedTagIds.addAll(draft);
                    button.setText(selectedTagIds.isEmpty()
                            ? "标签（未选择）"
                            : "标签（已选 " + selectedTagIds.size() + " 个）");
                })
                .show();
    }

    private ApiModels.Account selectedAccount(Spinner spinner) {
        Object selected = spinner.getSelectedItem();
        if (!(selected instanceof ApiModels.Account)) {
            throw new IllegalArgumentException("请选择账户");
        }
        return (ApiModels.Account) selected;
    }

    private ApiModels.Category selectedCategory(Spinner spinner) {
        Object selected = spinner.getSelectedItem();
        if (!(selected instanceof ApiModels.Category)) {
            throw new IllegalArgumentException("请选择分类");
        }
        return (ApiModels.Category) selected;
    }

    private List<ApiModels.Category> categoriesForMode(ApiModels.ReferenceData data, int mode) {
        if (mode == TYPE_INCOME) {
            return data.incomeCategories;
        }
        if (mode == TYPE_TRANSFER) {
            return data.transferCategories;
        }
        return data.expenseCategories;
    }

    private int typeToMode(String type) {
        if ("income".equals(type)) {
            return TYPE_INCOME;
        }
        if ("transfer".equals(type)) {
            return TYPE_TRANSFER;
        }
        return TYPE_EXPENSE;
    }

    private int countAccounts(List<ApiModels.AccountGroup> groups) {
        int count = 0;
        for (ApiModels.AccountGroup group : groups) {
            count += group.children.size();
        }
        return count;
    }

    private String titleForMode(int mode) {
        if (mode == TYPE_INCOME) {
            return "记收入";
        }
        if (mode == TYPE_TRANSFER) {
            return "记转账";
        }
        return "记支出";
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(spacing, spacing / 2, spacing, 0);
        return form;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout selectorHeaders(String left, String right) {
        LinearLayout headers = row();
        TextView leftView = label(left);
        TextView rightView = label(right);
        headers.addView(leftView, weighted());
        headers.addView(rightView, weighted());
        return headers;
    }

    private LinearLayout selectorRow(Spinner left, Spinner right) {
        LinearLayout selectors = row();
        selectors.addView(left, weighted());
        selectors.addView(right, weighted());
        return selectors;
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private EditText input(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(type);
        input.setSingleLine(true);
        return input;
    }

    private <T> Spinner spinner(List<T> items) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(spinnerAdapter(items));
        spinner.setBackgroundResource(R.drawable.spinner_rounded);
        spinner.setPopupBackgroundDrawable(getDrawable(R.drawable.spinner_popup_rounded));
        spinner.setMinimumHeight(Math.round(44 * getResources().getDisplayMetrics().density));
        return spinner;
    }

    private <T> ArrayAdapter<T> spinnerAdapter(List<T> items) {
        return new ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public android.view.View getDropDownView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                TextView item = new TextView(MainActivity.this);
                item.setText(getItem(position) == null ? "" : String.valueOf(getItem(position)));
                item.setTextSize(16);
                item.setTextColor(0xff202124);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(spacing, spacing / 2, spacing, spacing / 2);
                item.setBackgroundColor(0xFFFFFFFF);
                return item;
            }
        };
    }

    private TextView label(String value) {
        TextView label = text(value, 13, 0xff6b7280);
        label.setPadding(0, spacing / 2, 0, 0);
        return label;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.trim().isEmpty() ? "未知错误" : value;
    }
}

package com.froglike6.continuitybridge;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NotificationAppsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<NotificationAppCatalog.Entry> apps = new ArrayList<>();
    private NotificationPreferences preferences;
    private NotificationAppRows rows;
    private EditText search;
    private CheckBox systemApps;
    private TextView count, empty, feedback;
    private boolean loaded;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("알림을 보낼 앱");
        preferences = new NotificationPreferences(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int inset = size(R.dimen.continuity_space_4);
        root.setPadding(inset, inset, inset, inset);
        root.setFocusableInTouchMode(true);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this, null, android.R.attr.borderlessButtonStyle);
        back.setText("뒤로");
        back.setMinHeight(size(R.dimen.continuity_control_min_height));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { finish(); } });
        heading.addView(back);
        TextView title = text("알림을 보낼 앱", R.dimen.continuity_type_ui_status);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(heading, full());
        TextView introduction = text("새 앱은 자동으로 허용합니다.\n변경은 다음 알림부터 적용됩니다.", R.dimen.continuity_type_ui_body);
        root.addView(introduction, full());
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("앱 이름 또는 패키지 검색");
        search.setContentDescription("앱 이름 또는 패키지 검색");
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        search.setMinHeight(size(R.dimen.continuity_control_min_height));
        search.setText(state == null ? "" : state.getString("search", ""));
        root.addView(search, full());
        systemApps = new CheckBox(this);
        systemApps.setText("시스템 구성요소도 보기");
        systemApps.setMinHeight(size(R.dimen.continuity_control_min_height));
        systemApps.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        systemApps.setChecked(state != null && state.getBoolean("system_apps", false));
        root.addView(systemApps, full());
        count = text("앱 목록을 불러오는 중…", R.dimen.continuity_type_ui_caption);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(count, full());
        feedback = text("", R.dimen.continuity_type_ui_body);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        root.addView(feedback, full());
        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDividerHeight(size(R.dimen.continuity_divider_height));
        rows = new NotificationAppRows(this, preferences, new NotificationAppRows.OnChange() {
            @Override public void setAllowed(String packageName, boolean allowed) { change(packageName, allowed); }
        });
        list.setAdapter(rows);
        empty = text("설치된 앱을 확인하고 있습니다.", R.dimen.continuity_type_ui_body);
        empty.setGravity(Gravity.CENTER);
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView guidance = text("상시·무음·시스템 상태 알림은 제외합니다.\nMac 차단 설정도 확인해 주세요.", R.dimen.continuity_type_ui_caption);
        root.addView(guidance, full());
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                Rect visible = new Rect();
                root.getWindowVisibleDisplayFrame(visible);
                boolean keyboard = getResources().getDisplayMetrics().heightPixels - visible.bottom
                        > size(R.dimen.continuity_control_min_height) * 3;
                int visibility = keyboard ? View.GONE : View.VISIBLE;
                if (introduction.getVisibility() != visibility) {
                    introduction.setVisibility(visibility);
                    systemApps.setVisibility(visibility);
                    guidance.setVisibility(visibility);
                }
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int length, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int length) { filter(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        systemApps.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { filter(); }
        });
        root.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        worker.execute(new Runnable() { @Override public void run() {
            try {
                List<NotificationAppCatalog.Entry> installed = NotificationAppCatalog.load(getApplicationContext());
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (!isDestroyed()) { apps = installed; loaded = true; filter(); }
                }});
            } catch (RuntimeException error) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (!isDestroyed()) {
                        count.setText("앱 목록을 불러오지 못했습니다.");
                        empty.setText("뒤로 이동한 뒤 다시 열어 주세요.");
                    }
                }});
            }
        }});
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("search", search.getText().toString());
        state.putBoolean("system_apps", systemApps.isChecked());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private void change(String packageName, boolean allowed) {
        rows.saving(packageName);
        worker.execute(new Runnable() { @Override public void run() {
            boolean saved = preferences.setAllowed(packageName, allowed);
            runOnUiThread(new Runnable() { @Override public void run() {
                if (isDestroyed()) return;
                rows.saving(null);
                feedback.setVisibility(saved ? View.GONE : View.VISIBLE);
                feedback.setText(saved ? "" : "저장하지 못했습니다. 다시 선택해 주세요.");
                filter();
            }});
        }});
    }

    private void filter() {
        if (!loaded) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<NotificationAppCatalog.Entry> visible = new ArrayList<>();
        int allowed = 0;
        for (NotificationAppCatalog.Entry app : apps) {
            if ((!app.system || systemApps.isChecked()) && app.matches(query)) {
                visible.add(app);
                if (preferences.allows(app.packageName)) allowed++;
            }
        }
        rows.show(visible);
        count.setText("현재 목록: " + visible.size() + "개 · 전송 " + allowed + "개");
        empty.setText(query.isEmpty() ? "표시할 앱이 없습니다.\n시스템 구성요소도 보기로 확인해 주세요." : "검색 결과가 없습니다.\n앱 이름이나 패키지를 확인해 주세요.");
    }

    private TextView text(String content, int type) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextLocale(Locale.KOREAN);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(type));
        TypedValue color = new TypedValue();
        int role = type == R.dimen.continuity_type_ui_caption ? android.R.attr.textColorSecondary : android.R.attr.textColorPrimary;
        if (getTheme().resolveAttribute(role, color, true)) {
            if (color.resourceId != 0) view.setTextColor(getColorStateList(color.resourceId));
            else view.setTextColor(color.data);
        }
        view.setPadding(0, size(R.dimen.continuity_space_1), 0, size(R.dimen.continuity_space_1));
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            view.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        return view;
    }
    private int size(int resource) { return getResources().getDimensionPixelSize(resource); }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(-1, -2); }
}

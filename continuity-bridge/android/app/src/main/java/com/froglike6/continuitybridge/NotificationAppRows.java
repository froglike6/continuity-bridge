package com.froglike6.continuitybridge;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NotificationAppRows extends BaseAdapter {
    interface OnChange { void setAllowed(String packageName, boolean allowed); }
    private final Context context;
    private final NotificationPreferences preferences;
    private final OnChange change;
    private List<NotificationAppCatalog.Entry> visible = new ArrayList<>();
    private String savingPackage;

    NotificationAppRows(Context context, NotificationPreferences preferences, OnChange change) {
        this.context = context; this.preferences = preferences; this.change = change;
    }

    void show(List<NotificationAppCatalog.Entry> entries) { visible = entries; notifyDataSetChanged(); }
    void saving(String packageName) { savingPackage = packageName; notifyDataSetChanged(); }
    @Override public int getCount() { return visible.size(); }
    @Override public NotificationAppCatalog.Entry getItem(int position) { return visible.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        Row row = recycled == null ? new Row() : (Row) recycled.getTag();
        NotificationAppCatalog.Entry app = getItem(position);
        row.icon.setImageDrawable(app.icon);
        row.label.setText(app.label);
        row.details.setText(app.packageName);
        boolean allowed = preferences.allows(app.packageName);
        row.toggle.setOnCheckedChangeListener(null);
        row.toggle.setChecked(allowed);
        row.toggle.setText(allowed ? "전송" : "차단");
        row.toggle.setEnabled(savingPackage == null);
        row.toggle.setContentDescription(app.label + ", " + app.packageName + ", 알림 전송");
        row.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { change.setAllowed(app.packageName, checked); }
        });
        row.container.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { if (row.toggle.isEnabled()) row.toggle.performClick(); }
        });
        return row.container;
    }

    private final class Row {
        final LinearLayout container = new LinearLayout(context);
        final ImageView icon = new ImageView(context);
        final TextView label = text(R.dimen.continuity_type_ui_body);
        final TextView details = text(R.dimen.continuity_type_ui_caption);
        final Switch toggle = new Switch(context);

        Row() {
            container.setTag(this);
            container.setOrientation(LinearLayout.HORIZONTAL);
            container.setGravity(Gravity.CENTER_VERTICAL);
            container.setPadding(0, size(R.dimen.continuity_space_3), 0, size(R.dimen.continuity_space_3));
            container.setMinimumHeight(size(R.dimen.continuity_control_min_height));
            container.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            TypedValue selectable = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true))
                container.setBackgroundResource(selectable.resourceId);
            int iconSize = size(R.dimen.continuity_source_icon_size);
            LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLayout.setMarginEnd(size(R.dimen.continuity_space_3));
            container.addView(icon, iconLayout);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout names = new LinearLayout(context);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(0, 0, size(R.dimen.continuity_space_2), 0);
            names.addView(label);
            names.addView(details);
            names.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            container.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            toggle.setMinHeight(size(R.dimen.continuity_control_min_height));
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(R.dimen.continuity_type_ui_body));
            container.addView(toggle);
        }
    }

    private TextView text(int type) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(type));
        view.setTextLocale(Locale.KOREAN);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            view.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        TypedValue color = new TypedValue();
        int role = type == R.dimen.continuity_type_ui_caption ? android.R.attr.textColorSecondary : android.R.attr.textColorPrimary;
        if (context.getTheme().resolveAttribute(role, color, true)) {
            if (color.resourceId != 0) view.setTextColor(context.getColorStateList(color.resourceId));
            else view.setTextColor(color.data);
        }
        return view;
    }

    private int size(int resource) { return context.getResources().getDimensionPixelSize(resource); }
}

package com.froglike6.continuitybridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    private TextView helper;
    private EditText endpoint;
    private EditText pin;
    private EditText token;
    private CheckBox systemTrust;
    private Button start;
    private Button stop;
    private ConfigStore config;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusObserver = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (ConfigStore.RUNTIME_STATUS.equals(key)) runOnUiThread(new Runnable() { @Override public void run() { refresh(); } });
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("연속성 브리지 설정");
        config = new ConfigStore(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dimension(R.dimen.continuity_space_6), dimension(R.dimen.continuity_space_4), dimension(R.dimen.continuity_space_6), dimension(R.dimen.continuity_space_6));
        section(content, "상태"); LinearLayout statusGroup = group(content);
        status = label(statusGroup, "현재 상태", R.dimen.continuity_type_ui_status); status.setContentDescription("연결 상태");
        helper = label(statusGroup, "상태를 확인하는 중입니다.", R.dimen.continuity_type_ui_body);

        section(content, "보안 구성"); LinearLayout secureGroup = group(content);
        endpoint = field(secureGroup, "릴레이 HTTPS 주소", config.endpoint(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); divider(secureGroup);
        pin = field(secureGroup, "서버 인증서 SHA-256 핀", config.pin(), InputType.TYPE_CLASS_TEXT); divider(secureGroup);
        token = field(secureGroup, "기기 인증 토큰", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); divider(secureGroup);
        token.setContentDescription("기기 인증 토큰 보안 입력");
        systemTrust = new CheckBox(this); systemTrust.setText("시스템 신뢰 저장소 사용 (SHA-256 핀 없음)"); systemTrust.setChecked(config.systemTrust());
        systemTrust.setMinHeight(dimension(R.dimen.continuity_control_min_height)); systemTrust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { applyState(config.status()); }
        }); secureGroup.addView(systemTrust);

        section(content, "권한"); LinearLayout permissionGroup = group(content);
        Button overlay = button(permissionGroup, "다른 앱 위 표시 권한 열기"); overlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }}); divider(permissionGroup);
        Button listener = button(permissionGroup, "알림 접근 권한 열기"); listener.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }});

        section(content, "서비스"); LinearLayout serviceGroup = group(content);
        start = primaryButton(serviceGroup, "시작"); start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { startBridge(); } }); divider(serviceGroup);
        stop = button(serviceGroup, "중지"); stop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { confirmStop(); } });
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll); refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh();
    }

    @Override protected void onStart() { super.onStart(); config.observe(statusObserver); refresh(); }

    @Override protected void onStop() { config.stopObserving(statusObserver); super.onStop(); }

    private void startBridge() {
        try {
            config.save(endpoint.getText().toString().trim(), pin.getText().toString().trim(), systemTrust.isChecked());
            String enteredToken = token.getText().toString(); if (!enteredToken.isEmpty()) new TokenStore(this).put(enteredToken);
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                config.status(ConnectionStatus.PERMISSION_REQUIRED); requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 10); refresh(); return;
            }
            startForegroundService(new Intent(this, BridgeService.class)); helper.setText("서비스 연결을 시작했습니다."); refresh();
        } catch (Exception error) { helper.setText("설정을 확인해 주세요: " + safeReason(error)); }
    }

    private void confirmStop() {
        new AlertDialog.Builder(this).setTitle("서비스를 중지할까요?").setMessage("다시 시작할 때까지 연결이 중지됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("중지", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
                    stopService(new Intent(MainActivity.this, BridgeService.class)); config.status(ConnectionStatus.STOPPED); refresh();
                }}).show();
    }

    private void refresh() {
        if (status == null) return;
        ConnectionStatus value = config.status();
        ConnectionStatus reconciled = UiStatePolicy.reconcilePermissionStatus(value, notificationPermissionGranted());
        if (reconciled != value) { config.status(reconciled); value = reconciled; }
        status.setText(value.korean());
        status.setContentDescription("연결 상태: " + value.korean());
        if (value == ConnectionStatus.AUTH_FAILURE || value == ConnectionStatus.TLS_FAILURE || value == ConnectionStatus.SECURITY_FAILURE) helper.setText("설정과 보안 저장소를 확인한 뒤 다시 시작해 주세요.");
        else if (value == ConnectionStatus.PERMISSION_REQUIRED) helper.setText("알림 권한을 허용해야 서비스 상태를 계속 표시할 수 있습니다.");
        else if (value == ConnectionStatus.RETRY) helper.setText("일시적인 연결 문제로 제한된 간격 후 다시 시도합니다.");
        else helper.setText("클립보드: " + config.clipboardCapability() + "\n알림 접근: "
                + (config.listenerState().connected() ? "연결됨" : "연결 안 됨") + "\n알림 큐: "
                + config.notificationDeliveryStatus());
        applyState(value);
    }

    private boolean notificationPermissionGranted() {
        return android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void applyState(ConnectionStatus value) {
        UiStatePolicy.Decision decision = UiStatePolicy.forStatus(value, systemTrust.isChecked());
        setConfigurationControlState(endpoint, "릴레이 HTTPS 주소", decision.configurationEnabled(), value);
        setConfigurationControlState(token, "기기 인증 토큰 보안 입력", decision.configurationEnabled(), value);
        setConfigurationControlState(systemTrust, "시스템 신뢰 저장소 사용", decision.configurationEnabled(), value);
        pin.setEnabled(decision.pinEnabled()); if (!decision.pinEnabled()) resetDisabledFieldViewport(pin);
        start.setEnabled(decision.startEnabled()); stop.setEnabled(decision.stopEnabled());
        pin.setContentDescription(decision.pinEnabled() ? "서버 인증서 SHA-256 핀" : systemTrust.isChecked()
                ? "서버 인증서 SHA-256 핀: 시스템 신뢰 저장소를 사용하도록 선택되어 인증서 핀이 필요하지 않습니다."
                : "서버 인증서 SHA-256 핀: " + value.korean() + " 상태에서는 서비스 실행 중이라 설정을 변경할 수 없습니다.");
        start.setContentDescription(start.isEnabled() ? "시작: 연결을 시작합니다."
                : "시작: " + value.korean() + " 상태에서 서비스가 실행 중이거나 재시도 중이라 시작할 수 없습니다.");
        stop.setContentDescription(stop.isEnabled() ? "중지: 현재 연결을 중지합니다."
                : "중지: 서비스가 실행 중이 아니므로 중지할 대상이 없습니다.");
    }

    private void setConfigurationControlState(View control, String name, boolean enabled, ConnectionStatus value) {
        control.setEnabled(enabled);
        if (!enabled && control instanceof EditText) resetDisabledFieldViewport((EditText) control);
        control.setContentDescription(enabled ? name : name + ": " + value.korean() + " 상태에서는 서비스 실행 중이라 설정을 변경할 수 없습니다.");
    }

    private void resetDisabledFieldViewport(final EditText field) {
        field.clearFocus(); field.setSelection(0); field.scrollTo(0, 0);
        field.post(new Runnable() { @Override public void run() {
            if (!field.isEnabled()) { field.setSelection(0); field.scrollTo(0, 0); }
        }});
    }

    private EditText field(LinearLayout parent, String title, String value, int inputType) {
        label(parent, title, R.dimen.continuity_type_ui_body); EditText field = new EditText(this); field.setText(value); field.setSingleLine(true);
        field.setInputType(inputType); field.setMinHeight(dimension(R.dimen.continuity_control_min_height)); field.setContentDescription(title); parent.addView(field); return field;
    }
    private LinearLayout group(LinearLayout parent) { LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL); parent.addView(group); return group; }
    private void section(LinearLayout parent, String title) { TextView view = label(parent, title, R.dimen.continuity_type_ui_body); view.setTextAppearance(R.style.TextAppearance_ContinuityBridge_Section); view.setPadding(0, dimension(R.dimen.continuity_space_4), 0, dimension(R.dimen.continuity_space_1)); }
    private void divider(LinearLayout parent) {
        TypedValue value = new TypedValue(); View divider = new View(this);
        if (getTheme().resolveAttribute(android.R.attr.listDivider, value, true) && value.resourceId != 0) divider.setBackgroundResource(value.resourceId);
        parent.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimension(R.dimen.continuity_divider_height)));
    }
    private TextView label(LinearLayout parent, String text, int textSize) { TextView view = new TextView(this); view.setText(text); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(textSize)); view.setPadding(0, dimension(R.dimen.continuity_space_2), 0, dimension(R.dimen.continuity_space_1)); parent.addView(view); return view; }
    private Button button(LinearLayout parent, String text) { Button button = new Button(this); button.setText(text); button.setMinHeight(dimension(R.dimen.continuity_control_min_height)); parent.addView(button); return button; }
    private Button primaryButton(LinearLayout parent, String text) { Button button = new Button(this, null, 0, android.R.style.Widget_Material_Button_Colored); button.setText(text); button.setMinHeight(dimension(R.dimen.continuity_control_min_height)); parent.addView(button); return button; }
    private int dimension(int resourceId) { return getResources().getDimensionPixelSize(resourceId); }
    private static String safeReason(Exception error) { return error instanceof IllegalArgumentException ? "주소 또는 핀 형식이 올바르지 않습니다." : "보안 저장소를 사용할 수 없습니다."; }
}

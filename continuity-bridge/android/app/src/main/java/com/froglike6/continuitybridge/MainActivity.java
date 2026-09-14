package com.froglike6.continuitybridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 10;
    private TextView status;
    private TextView helper;
    private TextView actionFeedback;
    private TextView clipboardReadiness;
    private TextView listenerReadiness;
    private TextView deliveryReadiness;
    private TextView helperReadiness;
    private TextView credentialHelper;
    private TextView configurationHelper;
    private TextView saveFeedback;
    private TextView endpointError;
    private TextView pinError;
    private TextView tokenError;
    private TextView accessClientIdError;
    private TextView accessClientSecretError;
    private TextView accessCredentialHelper;
    private EditText endpoint;
    private EditText pin;
    private EditText token;
    private EditText accessClientId;
    private EditText accessClientSecret;
    private CheckBox systemTrust;
    private CheckBox accessEnabled;
    private Button start;
    private Button stop;
    private Button save;
    private Button disclosure;
    private Button stopAndEdit;
    private LinearLayout configurationGroup;
    private LinearLayout accessCredentialsGroup;
    private boolean configurationExpanded;
    private ConfigStore config;
    private EmbeddedHelperManager clipboardHelper;
    private final EmbeddedHelperManager.Observer helperObserver = new EmbeddedHelperManager.Observer() {
        @Override public void onState(EmbeddedHelperManager.State state) { refreshHelperReadiness(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener statusObserver = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (ConfigStore.RUNTIME_STATUS.equals(key) || "clipboard_capability".equals(key)
                    || "notification_listener".equals(key) || "notification_delivery".equals(key)) {
                runOnUiThread(new Runnable() { @Override public void run() { refresh(); } });
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("연속성 브리지");
        config = new ConfigStore(this);
        clipboardHelper = EmbeddedHelperManager.get(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dimension(R.dimen.continuity_space_6), dimension(R.dimen.continuity_space_6),
                dimension(R.dimen.continuity_space_6), dimension(R.dimen.continuity_space_8));
        content.setFocusableInTouchMode(true);
        identity(content);
        LinearLayout statusGroup = surface(content);
        TextView connectionLabel = caption(statusGroup, "릴레이 연결");
        semanticTextColor(connectionLabel, android.R.attr.colorAccent);
        status = label(statusGroup, "현재 상태", R.dimen.continuity_type_ui_status);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setAccessibilityHeading(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        helper = label(statusGroup, "상태를 확인하는 중입니다.", R.dimen.continuity_type_ui_body);
        secondary(helper);
        LinearLayout actions = group(statusGroup);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dimension(R.dimen.continuity_space_2), 0, 0);
        start = primaryButton(actions, "시작");
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { startBridge(); } });
        stop = textButton(actions, "중지");
        stop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { confirmStop(); } });
        LinearLayout.LayoutParams startLayout = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        startLayout.setMarginEnd(dimension(R.dimen.continuity_space_2));
        start.setLayoutParams(startLayout);
        stop.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        caption(statusGroup, "시작하면 입력한 설정을 저장하고 연결합니다.");
        actionFeedback = feedback(statusGroup);

        section(content, "공유 기능");
        LinearLayout readinessGroup = surface(content);
        clipboardReadiness = readiness(readinessGroup, "텍스트·사진 공유");
        caption(readinessGroup, "PNG · JPEG 원본, 최대 8 MiB\n사진 공유는 Mac과 릴레이에도 업데이트가 필요합니다.");
        divider(readinessGroup);
        deliveryReadiness = readiness(readinessGroup, "Android 알림 전달");
        caption(readinessGroup, "상시·무음·시스템 상태 알림은 제외합니다.\n다운로드 같은 작업 진행률은 전달합니다.");

        section(content, "권한");
        LinearLayout permissionsGroup = surface(content);
        listenerReadiness = readiness(permissionsGroup, "알림 접근");
        Button listener = permissionButton(permissionsGroup, "알림 접근 권한 열기");
        listener.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }});
        divider(permissionsGroup);
        helperReadiness = readiness(permissionsGroup, "클립보드 도우미");
        helperReadiness.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        caption(permissionsGroup, "복사할 때 키보드와 사용\u00a0중인 앱을 유지합니다.\n도우미가 꺼져 있어도 릴레이 연결과 알림 전달은 계속됩니다.");
        Button helperSetup = permissionButton(permissionsGroup, "클립보드 도우미 설정");
        helperSetup.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            startActivity(new Intent(MainActivity.this, HelperSetupActivity.class));
        }});
        caption(permissionsGroup, "처음 한 번, 이 앱에서 도우미를 연결하세요.");

        section(content, "고급 설정");
        LinearLayout serverGroup = surface(content);
        disclosure = permissionButton(serverGroup, "서버 연결 설정");
        disclosure.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        disclosure.setAccessibilityHeading(true);
        disclosure.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            setConfigurationExpanded(!configurationExpanded);
        }});
        configurationHelper = caption(serverGroup, "");
        configurationGroup = group(serverGroup);
        endpoint = field(configurationGroup, "릴레이 HTTPS 주소", state == null ? config.endpoint() : state.getString("draft_endpoint", config.endpoint()),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointError = fieldError(configurationGroup);
        caption(configurationGroup, "연결할 서버의 https:// 주소를 입력하세요.");
        systemTrust = new CheckBox(this);
        systemTrust.setText("시스템 신뢰 저장소 사용");
        systemTrust.setChecked(state == null ? config.systemTrust() : state.getBoolean("draft_system_trust", config.systemTrust()));
        systemTrust.setMinHeight(dimension(R.dimen.continuity_control_min_height));
        systemTrust.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        configurationGroup.addView(systemTrust, fullWidth());
        pin = field(configurationGroup, "서버 인증서 SHA-256 핀", state == null ? config.pin() : state.getString("draft_pin", config.pin()),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pin.setSingleLine(false);
        pin.setTypeface(Typeface.MONOSPACE);
        pinError = fieldError(configurationGroup);
        caption(configurationGroup, "시스템 신뢰를 사용하지 않으면 64자리 인증서 핀이 필요합니다.");
        token = field(configurationGroup, "기기 인증 토큰", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setContentDescription("기기 인증 토큰 보안 입력");
        token.setSaveEnabled(false);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        token.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        tokenError = fieldError(configurationGroup);
        credentialHelper = caption(configurationGroup, "");
        accessEnabled = new CheckBox(this);
        accessEnabled.setText("Cloudflare Access 사용");
        accessEnabled.setChecked(state == null ? config.accessEnabled() : state.getBoolean("draft_access_enabled", config.accessEnabled()));
        accessEnabled.setMinHeight(dimension(R.dimen.continuity_control_min_height));
        accessEnabled.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        configurationGroup.addView(accessEnabled, fullWidth());
        accessCredentialsGroup = group(configurationGroup);
        caption(accessCredentialsGroup, "이 기기의 Access 서비스 인증 정보를 입력하세요. 기기 인증 토큰도 함께 필요합니다.");
        AccessCredentials savedAccess = null;
        try { savedAccess = new TokenStore(this).loadAccess(); }
        catch (SecureStoreException error) { savedAccess = null; }
        accessClientId = field(accessCredentialsGroup, "Cloudflare Access Client ID", savedAccess == null ? "" : savedAccess.clientId(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        accessClientId.setSaveEnabled(false);
        accessClientId.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        accessClientId.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        accessClientIdError = fieldError(accessCredentialsGroup);
        accessClientSecret = field(accessCredentialsGroup, "Cloudflare Access Client Secret", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        accessClientSecret.setContentDescription("Cloudflare Access Client Secret 보안 입력");
        accessClientSecret.setSaveEnabled(false);
        accessClientSecret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        accessClientSecret.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        accessClientSecretError = fieldError(accessCredentialsGroup);
        accessCredentialHelper = caption(accessCredentialsGroup, "");
        save = button(configurationGroup, "설정 저장");
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { saveConfiguration(); } });
        saveFeedback = feedback(configurationGroup);
        stopAndEdit = button(configurationGroup, "중지하고 설정 변경");
        stopAndEdit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { confirmStop(true); } });
        systemTrust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                clearFieldError(pin, pinError);
                configurationEdited();
                applyState(config.status());
            }
        });
        accessEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                clearFieldError(accessClientId, accessClientIdError);
                clearFieldError(accessClientSecret, accessClientSecretError);
                configurationEdited();
                applyState(config.status());
                refreshAccessCredentialHelper();
            }
        });
        watchField(endpoint, endpointError);
        watchField(pin, pinError);
        watchField(token, tokenError);
        watchField(accessClientId, accessClientIdError);
        watchField(accessClientSecret, accessClientSecretError);
        setConfigurationExpanded(state == null ? configurationNeedsAttention() : state.getBoolean("configuration_expanded", false));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(semanticColor(android.R.attr.colorBackground));
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        content.requestFocus();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("draft_endpoint", endpoint.getText().toString());
        state.putString("draft_pin", pin.getText().toString());
        state.putBoolean("draft_system_trust", systemTrust.isChecked());
        state.putBoolean("draft_access_enabled", accessEnabled.isChecked());
        state.putBoolean("configuration_expanded", configurationExpanded);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean notificationRequest = requestCode == NOTIFICATION_PERMISSION_REQUEST && permissions.length == 1
                && Manifest.permission.POST_NOTIFICATIONS.equals(permissions[0]);
        boolean granted = grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        ConnectionStatus current = config.status();
        ConnectionStatus reconciled = UiStatePolicy.afterPermissionResult(current, notificationRequest, granted);
        if (reconciled != current) config.status(reconciled);
        refresh();
    }

    @Override protected void onStart() {
        super.onStart(); config.observe(statusObserver); clipboardHelper.addObserver(helperObserver); refresh();
    }

    @Override protected void onStop() {
        clipboardHelper.removeObserver(helperObserver); config.stopObserving(statusObserver); super.onStop();
    }

    private void startBridge() {
        if (!UiStatePolicy.forStatus(config.status(), systemTrust.isChecked()).startEnabled()) return;
        if (!saveConfiguration()) {
            showFeedback(actionFeedback, "연결 설정을 확인해 주세요.");
            return;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                config.status(ConnectionStatus.PERMISSION_REQUIRED); requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_PERMISSION_REQUEST); refresh(); return;
            }
            startForegroundService(new Intent(this, BridgeService.class));
            showFeedback(actionFeedback, "설정을 저장했습니다. 연결 상태를 확인하고 있습니다.");
            refresh();
        } catch (RuntimeException error) {
            showFeedback(actionFeedback, "서비스를 시작하지 못했습니다. 권한을 확인한 뒤 다시 시작해 주세요.");
        }
    }

    private boolean saveConfiguration() {
        if (!UiStatePolicy.forStatus(config.status(), systemTrust.isChecked()).configurationEnabled()) return false;
        clearFieldError(endpoint, endpointError);
        clearFieldError(pin, pinError);
        clearFieldError(token, tokenError);
        clearFieldError(accessClientId, accessClientIdError);
        clearFieldError(accessClientSecret, accessClientSecretError);
        String address = endpoint.getText().toString().trim();
        String certificatePin = pin.getText().toString().trim().toLowerCase(Locale.ROOT);
        String enteredToken = token.getText().toString();
        AccessCredentials preparedAccess = null;
        EditText invalid = null;
        try { ConfigValidator.httpsUrl(address); }
        catch (IllegalArgumentException error) {
            setFieldError(endpoint, endpointError, "https://로 시작하는 올바른 서버 주소를 입력해 주세요.");
            invalid = endpoint;
        }
        if (!systemTrust.isChecked()) {
            try { ConfigValidator.pin(certificatePin); }
            catch (IllegalArgumentException error) {
                setFieldError(pin, pinError, "0–9와 a–f로 된 64자리 인증서 핀을 입력해 주세요.");
                if (invalid == null) invalid = pin;
            }
        }
        if (enteredToken.isEmpty() && !hasStoredToken()) {
            setFieldError(token, tokenError, "처음 연결하려면 기기 인증 토큰을 입력해 주세요.");
            if (invalid == null) invalid = token;
        }
        try { preparedAccess = enteredAccessCredentials(); }
        catch (AccessCredentials.ValidationException error) {
            boolean idInvalid = error.field() == AccessCredentials.Field.CLIENT_ID;
            EditText field = idInvalid ? accessClientId : accessClientSecret;
            setFieldError(field, idInvalid ? accessClientIdError : accessClientSecretError, idInvalid
                    ? "공백이나 줄바꿈 없이 이 기기의 Client ID를 입력해 주세요."
                    : "Client Secret을 입력해 주세요. 비워 두려면 저장된 Client ID와 같아야 하며 공백·줄바꿈은 사용할 수 없습니다.");
            if (invalid == null) invalid = field;
        } catch (SecureStoreException error) {
            setFieldError(accessClientSecret, accessClientSecretError, "저장된 Access 인증 정보를 읽지 못했습니다. Client Secret을 다시 입력해 저장해 주세요.");
            if (invalid == null) invalid = accessClientSecret;
        }
        if (invalid != null) {
            setConfigurationExpanded(true);
            showFeedback(saveFeedback, "저장하지 못했습니다. 표시된 입력 항목을 확인해 주세요.");
            invalid.requestFocus();
            return false;
        }
        try {
            if (!enteredToken.isEmpty()) new TokenStore(this).put(enteredToken);
            if (preparedAccess != null && !accessClientSecret.getText().toString().isEmpty()) new TokenStore(this).putAccess(preparedAccess);
            config.save(address, certificatePin, systemTrust.isChecked(), accessEnabled.isChecked());
            endpoint.setText(address);
            pin.setText(systemTrust.isChecked() ? "" : certificatePin);
            token.setText("");
            accessClientSecret.setText("");
            refreshCredentialHelper();
            showFeedback(saveFeedback, "설정을 저장했습니다. 다음 시작부터 사용합니다.");
            actionFeedback.setVisibility(View.GONE);
            refresh();
            return true;
        } catch (Exception error) {
            setConfigurationExpanded(true);
            showFeedback(saveFeedback, "설정 저장을 완료하지 못했습니다. 보안 저장소를 확인한 뒤 다시 시도해 주세요.");
            return false;
        }
    }

    private void confirmStop() { confirmStop(false); }

    private void confirmStop(final boolean editAfterStop) {
        new AlertDialog.Builder(this).setTitle("서비스를 중지할까요?").setMessage("다시 시작할 때까지 연결이 중지됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("중지", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
                    clipboardHelper.onUserStop();
                    stopService(new Intent(MainActivity.this, BridgeService.class)); config.status(ConnectionStatus.STOPPED); refresh();
                    actionFeedback.setVisibility(View.GONE);
                    if (editAfterStop) { setConfigurationExpanded(true); endpoint.requestFocus(); }
                }}).show();
    }

    private void refresh() {
        if (status == null) return;
        ConnectionStatus value = config.status();
        updateText(status, value.korean());
        status.setContentDescription("릴레이 연결 상태: " + value.korean());
        updateText(helper, connectionExplanation(value));
        if (value == ConnectionStatus.CONNECTED || value == ConnectionStatus.AUTH_FAILURE || value == ConnectionStatus.TLS_FAILURE
                || value == ConnectionStatus.SECURITY_FAILURE || value == ConnectionStatus.PERMISSION_REQUIRED) actionFeedback.setVisibility(View.GONE);
        refreshReadiness(value);
        refreshCredentialHelper();
        refreshAccessCredentialHelper();
        applyState(value);
    }

    private String connectionExplanation(ConnectionStatus value) {
        switch (value) {
            case CONNECTED: return "릴레이 서버에 연결되어 있습니다. 다른 기기의 접속 여부는 확인하지 않습니다.";
            case CLIPBOARD_WAIT: return "클립보드 접근을 기다립니다.\n준비되면 자동으로 복사를 이어갑니다.";
            case CONNECTING: return "릴레이 서버와 보안 연결을 확인하고 있습니다.";
            case AUTH_FAILURE: return "인증이 거부되었거나 로그인 화면으로 이동할 수 없습니다. 서버 연결 설정의 기기 토큰과 Access 인증 정보를 확인한 뒤 다시 시작해 주세요.";
            case TLS_FAILURE: return "서버 인증서를 확인하지 못했습니다. 서버 연결 설정의 주소와 인증서 신뢰 설정을 확인해 주세요.";
            case SECURITY_FAILURE: return "보안 저장소를 사용할 수 없습니다. 서버 연결 설정에서 기기 토큰과 사용하는 Access 인증 정보를 다시 저장한 뒤 시작해 주세요.";
            case PERMISSION_REQUIRED: return notificationPermissionGranted()
                    ? "클립보드 도우미를 확인해야 합니다. 아래 도우미 준비 상태를 확인해 주세요."
                    : "알림 권한을 허용해야 서비스 상태를 계속 표시할 수 있습니다.";
            case RETRY: return "일시적인 연결 문제로 잠시 후 자동으로 다시 시도합니다.";
            case STOPPED: return "시작하면 릴레이 서버에 연결합니다.";
            default: return "현재 릴레이 연결이 없습니다. 시작을 눌러 다시 연결해 주세요.";
        }
    }

    private void refreshReadiness(ConnectionStatus value) {
        boolean active = UiStatePolicy.forStatus(value, systemTrust.isChecked()).stopEnabled();
        refreshHelperReadiness();
        NotificationManager manager = getSystemService(NotificationManager.class);
        boolean listenerAllowed = manager.isNotificationListenerAccessGranted(new ComponentName(this, NotificationMirrorService.class));
        updateText(listenerReadiness, listenerAllowed
                ? "접근 허용됨 · 마지막 서비스 연결: " + (config.listenerState().connected() ? "연결됨" : "연결 안 됨")
                : "접근 권한 필요 · Android 알림을 전달하려면 허용하세요.");
        updateText(deliveryReadiness, (active ? "마지막 큐 상태: " : "전송 중지됨 · 마지막 큐 상태: ")
                + config.notificationDeliveryStatus() + "\nMac 알림 표시: 확인 안 함");
    }

    private void refreshHelperReadiness() {
        if (helperReadiness == null) return;
        EmbeddedHelperManager.State state = android.os.Build.VERSION.SDK_INT < 30
                ? EmbeddedHelperManager.State.UNSUPPORTED : clipboardHelper.state();
        String explanation = HelperStatusText.title(state) + "\n" + HelperStatusText.explanation(state, clipboardHelper.paired());
        updateText(helperReadiness, explanation);
        helperReadiness.setContentDescription("클립보드 도우미: " + explanation);
        boolean active = UiStatePolicy.forStatus(config.status(), systemTrust.isChecked()).stopEnabled();
        String sharing;
        if (!active) sharing = "서비스 중지됨 · 복사 감지는 서비스 시작 후 확인합니다.";
        else sharing = state == EmbeddedHelperManager.State.READY ? "최근 감지 상태: " + config.clipboardCapability()
                : "클립보드 공유 대기 · 아래 클립보드 도우미를 확인해 주세요.";
        updateText(clipboardReadiness, sharing);
    }

    private boolean notificationPermissionGranted() {
        return android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void applyState(ConnectionStatus value) {
        UiStatePolicy.Decision decision = UiStatePolicy.forStatus(value, systemTrust.isChecked());
        setConfigurationControlState(endpoint, "릴레이 HTTPS 주소", decision.configurationEnabled(), value);
        setConfigurationControlState(token, "기기 인증 토큰 보안 입력", decision.configurationEnabled(), value);
        setConfigurationControlState(systemTrust, "시스템 신뢰 저장소 사용", decision.configurationEnabled(), value);
        setConfigurationControlState(accessEnabled, "Cloudflare Access 사용", decision.configurationEnabled(), value);
        setConfigurationControlState(accessClientId, "Cloudflare Access Client ID", decision.configurationEnabled(), value);
        setConfigurationControlState(accessClientSecret, "Cloudflare Access Client Secret 보안 입력", decision.configurationEnabled(), value);
        accessCredentialsGroup.setVisibility(accessEnabled.isChecked() ? View.VISIBLE : View.GONE);
        pin.setEnabled(decision.pinEnabled()); if (!decision.pinEnabled()) resetDisabledFieldViewport(pin);
        start.setEnabled(decision.startEnabled()); stop.setEnabled(decision.stopEnabled());
        save.setEnabled(decision.configurationEnabled());
        stopAndEdit.setVisibility(decision.stopEnabled() ? View.VISIBLE : View.GONE);
        updateText(configurationHelper, decision.configurationEnabled()
                ? "주소·인증서·토큰과 필요한 경우 Access 인증 정보를 설정하세요."
                : "중지 후 변경 · 실행 중에는 현재 설정을 사용합니다.");
        pin.setContentDescription(decision.pinEnabled() ? "서버 인증서 SHA-256 핀" : systemTrust.isChecked()
                ? "서버 인증서 SHA-256 핀: 시스템 신뢰 저장소를 사용하도록 선택되어 인증서 핀이 필요하지 않습니다."
                : "서버 인증서 SHA-256 핀: " + value.korean() + " 상태에서는 서비스 실행 중이라 설정을 변경할 수 없습니다.");
        start.setContentDescription(start.isEnabled() ? "시작: 입력한 설정을 저장하고 릴레이 연결을 시작합니다."
                : "시작: " + value.korean() + " 상태에서 서비스가 실행 중이거나 재시도 중이라 시작할 수 없습니다.");
        stop.setContentDescription(stop.isEnabled() ? "중지: 현재 연결을 중지합니다."
                : "중지: 서비스가 실행 중이 아니므로 중지할 대상이 없습니다.");
        save.setContentDescription(save.isEnabled() ? "설정 저장: 다음 시작에 사용할 설정을 저장합니다."
                : "설정 저장: 서비스 중지 후 변경할 수 있습니다.");
    }

    private boolean hasStoredToken() {
        return getSharedPreferences("bridge_secret_envelope", MODE_PRIVATE).contains("token_envelope");
    }

    private void refreshCredentialHelper() {
        updateText(credentialHelper, hasStoredToken()
                ? "저장된 토큰 있음\n비워 두면 기존 토큰을 사용합니다."
                : "저장된 토큰 없음\n처음 연결할 때 입력해 주세요.");
    }

    private AccessCredentials enteredAccessCredentials() throws SecureStoreException {
        if (!accessEnabled.isChecked()) return null;
        String clientId = accessClientId.getText().toString();
        AccessCredentials.validateClientId(clientId);
        String secret = accessClientSecret.getText().toString();
        AccessCredentials saved = secret.isEmpty() ? new TokenStore(this).loadAccess() : null;
        return AccessCredentials.forSave(clientId, secret, saved);
    }

    private void refreshAccessCredentialHelper() {
        try {
            AccessCredentials saved = new TokenStore(this).loadAccess();
            updateText(accessCredentialHelper, saved == null
                    ? "저장된 Access 인증 정보 없음\nClient ID와 Client Secret을 입력해 주세요."
                    : saved.clientId().equals(accessClientId.getText().toString())
                            ? "저장된 Access 인증 정보 있음\nSecret을 비워 두면 저장된 값을 사용합니다."
                            : "Client ID가 변경되었습니다.\n새 Client Secret을 함께 입력해 주세요.");
        } catch (SecureStoreException error) {
            updateText(accessCredentialHelper, "저장된 Access 인증 정보를 읽을 수 없습니다.\nClient ID와 Client Secret을 다시 입력해 주세요.");
        }
    }

    private boolean configurationNeedsAttention() {
        if (!hasStoredToken()) return true;
        try { enteredAccessCredentials(); }
        catch (SecureStoreException | AccessCredentials.ValidationException error) { return true; }
        try {
            ConfigValidator.httpsUrl(endpoint.getText().toString().trim());
            if (!systemTrust.isChecked()) ConfigValidator.pin(pin.getText().toString().trim().toLowerCase(Locale.ROOT));
            return false;
        } catch (IllegalArgumentException error) { return true; }
    }

    private void setConfigurationExpanded(boolean expanded) {
        configurationExpanded = expanded;
        configurationGroup.setVisibility(expanded ? View.VISIBLE : View.GONE);
        disclosure.setText(expanded ? "서버 연결 설정 접기" : "서버 연결 설정 펼치기");
        disclosure.setContentDescription(expanded ? "서버 연결 설정, 펼쳐짐. 눌러서 접기" : "서버 연결 설정, 접힘. 눌러서 펼치기");
    }

    private void configurationEdited() {
        showFeedback(saveFeedback, "변경 내용을 저장해 주세요. 시작할 때도 저장합니다.");
        actionFeedback.setVisibility(View.GONE);
    }

    private void watchField(final EditText field, final TextView error) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                clearFieldError(field, error);
                configurationEdited();
                if (field == accessClientId) refreshAccessCredentialHelper();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
    }

    private void setFieldError(EditText field, TextView label, String message) {
        field.setError(null);
        showFeedback(label, message);
    }

    private void clearFieldError(EditText field, TextView label) {
        field.setError(null);
        label.setVisibility(View.GONE);
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
        TextView label = label(parent, title, R.dimen.continuity_type_ui_body);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, dimension(R.dimen.continuity_space_4), 0, dimension(R.dimen.continuity_space_1));
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setText(value);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        field.setMinHeight(dimension(R.dimen.continuity_control_min_height));
        field.setContentDescription(title);
        parent.addView(field, fullWidth());
        return field;
    }
    private void identity(LinearLayout parent) {
        LinearLayout identity = group(parent);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams identityLayout = fullWidth();
        identityLayout.bottomMargin = dimension(R.dimen.continuity_space_6);
        identity.setLayoutParams(identityLayout);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dimension(R.dimen.continuity_identity_size), dimension(R.dimen.continuity_identity_size));
        iconLayout.setMarginEnd(dimension(R.dimen.continuity_space_4));
        identity.addView(icon, iconLayout);
        LinearLayout text = group(identity);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView title = label(text, "연속성 브리지", R.dimen.continuity_type_ui_title);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, 0, 0, 0);
        title.setAccessibilityHeading(true);
        caption(text, "Mac · Android");
    }
    private LinearLayout group(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        parent.addView(group, fullWidth());
        return group;
    }
    private LinearLayout surface(LinearLayout parent) {
        LinearLayout surface = group(parent);
        int inset = dimension(R.dimen.continuity_space_4);
        surface.setPadding(inset, inset, inset, inset);
        GradientDrawable background = new GradientDrawable();
        background.setColor(semanticColor(android.R.attr.colorBackgroundFloating));
        background.setCornerRadius(dimension(R.dimen.continuity_surface_radius));
        surface.setBackground(background);
        return surface;
    }
    private void section(LinearLayout parent, String title) {
        TextView view = label(parent, title, R.dimen.continuity_type_ui_body);
        view.setTextAppearance(R.style.TextAppearance_ContinuityBridge_Section);
        view.setAccessibilityHeading(true);
        view.setPadding(0, dimension(R.dimen.continuity_space_6), 0, dimension(R.dimen.continuity_space_2));
    }
    private TextView readiness(LinearLayout parent, String title) {
        TextView heading = label(parent, title, R.dimen.continuity_type_ui_body);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, 0, 0, dimension(R.dimen.continuity_space_1));
        TextView value = label(parent, "확인 중", R.dimen.continuity_type_ui_body);
        value.setPadding(0, 0, 0, 0);
        secondary(value);
        return value;
    }
    private void divider(LinearLayout parent) {
        TypedValue value = new TypedValue(); View divider = new View(this);
        if (getTheme().resolveAttribute(android.R.attr.listDivider, value, true) && value.resourceId != 0) divider.setBackgroundResource(value.resourceId);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimension(R.dimen.continuity_divider_height));
        layout.topMargin = dimension(R.dimen.continuity_space_3);
        layout.bottomMargin = dimension(R.dimen.continuity_space_3);
        parent.addView(divider, layout);
    }
    private TextView label(LinearLayout parent, String text, int textSize) {
        TextView view = new TextView(this);
        view.setTextLocale(Locale.KOREAN);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            view.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        }
        KoreanUiText.set(view, text);
        semanticTextColor(view, android.R.attr.textColorPrimary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(textSize));
        view.setLineSpacing(dimension(R.dimen.continuity_space_1), 1);
        view.setPadding(0, dimension(R.dimen.continuity_space_2), 0, dimension(R.dimen.continuity_space_1));
        parent.addView(view, fullWidth());
        return view;
    }
    private TextView caption(LinearLayout parent, String text) {
        TextView view = label(parent, text, R.dimen.continuity_type_ui_caption);
        view.setPadding(0, dimension(R.dimen.continuity_space_1), 0, 0);
        secondary(view);
        return view;
    }
    private TextView feedback(LinearLayout parent) {
        TextView view = label(parent, "", R.dimen.continuity_type_ui_body);
        view.setVisibility(View.GONE);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }
    private TextView fieldError(LinearLayout parent) {
        TextView view = feedback(parent);
        semanticTextColor(view, android.R.attr.colorError);
        return view;
    }
    private void showFeedback(TextView view, String message) { updateText(view, message); view.setVisibility(View.VISIBLE); }
    private void updateText(TextView view, String text) { if (!TextUtils.equals(view.getText(), text)) KoreanUiText.set(view, text); }
    private void secondary(TextView view) { semanticTextColor(view, android.R.attr.textColorSecondary); }
    private void semanticTextColor(TextView view, int attribute) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) view.setTextColor(getColorStateList(value.resourceId));
            else view.setTextColor(value.data);
        }
    }
    private int semanticColor(int attribute) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId == 0 ? value.data : getColor(value.resourceId);
    }
    private Button button(LinearLayout parent, String text) { return addButton(parent, new Button(this), text); }
    private Button textButton(LinearLayout parent, String text) {
        return addButton(parent, new Button(this, null, android.R.attr.borderlessButtonStyle), text);
    }
    private Button permissionButton(LinearLayout parent, String text) {
        Button button = textButton(parent, text);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(0, 0, 0, 0);
        return button;
    }
    private Button primaryButton(LinearLayout parent, String text) {
        return addButton(parent, new Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Colored), text);
    }
    private Button addButton(LinearLayout parent, Button button, String text) {
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        button.setSingleLine(false);
        button.setMinWidth(0);
        button.setMinHeight(dimension(R.dimen.continuity_control_min_height));
        parent.addView(button, fullWidth());
        return button;
    }
    private LinearLayout.LayoutParams fullWidth() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private int dimension(int resourceId) { return getResources().getDimensionPixelSize(resourceId); }
}

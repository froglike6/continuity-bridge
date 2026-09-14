package com.froglike6.continuitybridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

public final class HelperSetupActivity extends Activity {
    private EmbeddedHelperManager manager;
    private EmbeddedHelperManager.State previousState;
    private EmbeddedHelperManager.State pendingState;
    private ScrollView scroll;
    private TextView status, explanation, savedConnection, feedback, settingsFeedback;
    private TextView codeError, portError, pairingSettingsFeedback;
    private LinearLayout pairingGroup;
    private EditText code, port;
    private CheckBox automaticRecovery;
    private Button retry, newCode, settings, debuggingSettings, pair;
    private boolean manualPairing;
    private final EmbeddedHelperManager.Observer observer = new EmbeddedHelperManager.Observer() {
        @Override public void onState(EmbeddedHelperManager.State state) {
            pendingState = null;
            render(state);
        }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setTitle("클립보드 도우미");
        manager = EmbeddedHelperManager.get(this);
        manualPairing = savedState != null && savedState.getBoolean("manual_pairing", false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(size(R.dimen.continuity_space_4), size(R.dimen.continuity_space_4),
                size(R.dimen.continuity_space_4), 0);
        root.setBackgroundColor(color(android.R.attr.colorBackground));
        root.setFocusableInTouchMode(true);
        LinearLayout heading = group(root);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button(heading, "뒤로", false);
        back.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { finish(); } });
        TextView title = label(heading, "클립보드 도우미", R.dimen.continuity_type_ui_status);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setAccessibilityHeading(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, size(R.dimen.continuity_space_4), 0, size(R.dimen.continuity_space_8));
        scroll.addView(content);

        LinearLayout summary = surface(content);
        caption(summary, "이 휴대폰에서만 연결");
        status = label(summary, "확인 중", R.dimen.continuity_type_ui_status);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setAccessibilityHeading(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        explanation = label(summary, "", R.dimen.continuity_type_ui_body);
        savedConnection = caption(summary, "");
        retry = button(summary, "연결", true);
        retry.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            pendingState = EmbeddedHelperManager.State.STARTING;
            feedback.setVisibility(View.GONE);
            render(manager.state());
            manager.start();
        }});
        debuggingSettings = button(summary, "개발자 옵션 열기", false);
        debuggingSettings.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { openSettings(); } });
        settingsFeedback = feedback(summary);
        newCode = button(summary, "새 페어링 코드로 연결", false);
        newCode.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) {
            manualPairing = !manualPairing;
            code.getText().clear();
            feedback.setVisibility(View.GONE);
            render(manager.state());
        }});

        pairingGroup = group(content);
        section(pairingGroup, "처음 연결 · 페어링 코드");
        LinearLayout instructions = surface(pairingGroup);
        label(instructions, "설정과 이 앱을 분할 화면으로 여세요.", R.dimen.continuity_type_ui_body)
                .setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label(instructions, "1. 개발자 옵션에서 무선 디버깅을 켜세요.\n"
                + "2. 최근 앱에서 설정과 연속성 브리지를 분할 화면으로 여세요.\n"
                + "3. 설정의 ‘페어링 코드로 기기 페어링’ 항목을 누르세요. 코드 창을 열어두고 아래에 입력하세요.", R.dimen.continuity_type_ui_body);
        caption(instructions, "앱을 전환하면 코드 창이 닫힐\u00a0수\u00a0있습니다. 먼저 분할 화면을 여세요.");
        settings = button(instructions, "개발자 옵션 열기", false);
        settings.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { openSettings(); } });
        pairingSettingsFeedback = feedback(instructions);
        caption(instructions, "직접 열기: Android 설정 → 개발자 옵션 → 무선 디버깅\n"
                + "개발자 옵션이 없으면 ‘빌드 번호’ 항목을 7번 누르세요. 메뉴 위치는 기기에 따라 다릅니다.");
        code = field(pairingGroup, "페어링 코드 · 6자리", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setTransformationMethod(PasswordTransformationMethod.getInstance());
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setContentDescription("페어링 코드 6자리 보안 입력");
        code.setSaveEnabled(false);
        code.setSaveFromParentEnabled(false);
        code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (Build.VERSION.SDK_INT >= 29) code.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        code.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        code.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                submitPairing();
                return true;
            }
        });
        codeError = feedback(pairingGroup);
        clearErrorOnEdit(code, codeError);
        caption(pairingGroup, "코드는 이 휴대폰에서만 사용합니다. 저장하지\u00a0않습니다.");
        port = field(pairingGroup, "페어링 포트 · 선택 사항", InputType.TYPE_CLASS_NUMBER);
        port.setHint("비워 두면 자동으로 찾기");
        port.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        port.setText(savedState == null ? "" : savedState.getString("pairing_port", ""));
        port.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        portError = feedback(pairingGroup);
        clearErrorOnEdit(port, portError);
        caption(pairingGroup, "자동으로 찾지 못할 때만 입력하세요. 페어링 코드 창에서 콜론 뒤 숫자를 확인하세요. 무선 디버깅 기본 화면의 포트와\u00a0다릅니다.");
        pair = button(pairingGroup, "코드로 연결", true);
        pair.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { submitPairing(); } });
        feedback = feedback(pairingGroup);

        section(content, "재부팅 후 연결");
        LinearLayout recovery = surface(content);
        automaticRecovery = new CheckBox(this);
        automaticRecovery.setText("재부팅 후 자동으로 다시 연결");
        automaticRecovery.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        automaticRecovery.setSingleLine(false);
        automaticRecovery.setMinHeight(size(R.dimen.continuity_control_min_height));
        automaticRecovery.setChecked(Build.VERSION.SDK_INT >= 33 && manager.automaticRecovery());
        recovery.addView(automaticRecovery, fullWidth());
        automaticRecovery.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { manager.setAutomaticRecovery(checked); }
        });
        if (Build.VERSION.SDK_INT < 33)
            caption(recovery, "재부팅 후 자동 연결은 Android 13 이상에서 지원합니다.");
        label(recovery, "켜면 재부팅 뒤 처음 잠금을 해제하고 신뢰하는 Wi-Fi에 연결되었을 때, 이 앱이 휴대폰의 무선 디버깅을 켜고 도우미 연결을 시도합니다.", R.dimen.continuity_type_ui_body);
        caption(recovery, "일부 기기에서는 무선 디버깅이나 페어링을 직접 설정해야\u00a0합니다. 홈에서 중지하면 자동으로 재개하지\u00a0않습니다. 홈의 시작 버튼을 누르면 다시 연결합니다.");
        caption(content, "도우미 설정은 기존 서버 연결 설정을 그대로 유지합니다. 도우미가 꺼져도 실행 중인 릴레이와 알림 전달은 계속됩니다.");
        setContentView(root);
        root.requestFocus();
        render(manager.state());
    }

    @Override protected void onStart() { super.onStart(); manager.addObserver(observer); render(manager.state()); }
    @Override protected void onResume() { super.onResume(); render(manager.state()); }
    @Override protected void onStop() {
        manager.removeObserver(observer);
        code.getText().clear();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("manual_pairing", manualPairing);
        state.putString("pairing_port", port.getText().toString());
        super.onSaveInstanceState(state);
    }

    private void render(EmbeddedHelperManager.State observed) {
        if (status == null || isDestroyed()) return;
        EmbeddedHelperManager.State state = Build.VERSION.SDK_INT < 30 ? EmbeddedHelperManager.State.UNSUPPORTED
                : pendingState == null ? observed : pendingState;
        boolean becameReady = state == EmbeddedHelperManager.State.READY
                && previousState != null && previousState != state;
        if (becameReady) manualPairing = false;
        previousState = state;
        boolean supported = state != EmbeddedHelperManager.State.UNSUPPORTED;
        boolean paired = manager.paired();
        boolean busy = HelperStatusText.busy(state);
        boolean needsCode = !paired || state == EmbeddedHelperManager.State.PAIRING_REQUIRED
                || state == EmbeddedHelperManager.State.PAIRING || manualPairing;
        setText(status, HelperStatusText.title(state));
        setText(explanation, HelperStatusText.explanation(state, paired));
        setText(savedConnection, paired ? "저장된 연결 있음 · 페어링 코드는 저장하지 않음" : "저장된 연결 없음");
        savedConnection.setVisibility(supported ? View.VISIBLE : View.GONE);
        pairingGroup.setVisibility(supported && needsCode ? View.VISIBLE : View.GONE);
        retry.setVisibility(supported && paired && !needsCode && state != EmbeddedHelperManager.State.READY ? View.VISIBLE : View.GONE);
        retry.setEnabled(!busy);
        retry.setText(busy ? "연결 중" : state == EmbeddedHelperManager.State.STOPPED ? "연결" : "다시 연결");
        debuggingSettings.setVisibility(state == EmbeddedHelperManager.State.DEBUGGING_REQUIRED && !needsCode ? View.VISIBLE : View.GONE);
        newCode.setVisibility(supported && paired && state != EmbeddedHelperManager.State.PAIRING_REQUIRED && !busy ? View.VISIBLE : View.GONE);
        newCode.setText(manualPairing ? "코드 입력 접기" : "새 페어링 코드로 연결");
        newCode.setContentDescription(manualPairing ? "새 페어링 코드 입력, 펼쳐짐. 눌러서 접기" : "새 페어링 코드 입력, 접힘. 눌러서 펼치기");
        settings.setEnabled(supported && !busy);
        code.setEnabled(supported && !busy);
        port.setEnabled(supported && !busy);
        pair.setEnabled(supported && !busy);
        pair.setText(state == EmbeddedHelperManager.State.PAIRING ? "코드 확인 중" : busy ? "도우미 연결 중" : "코드로 연결");
        automaticRecovery.setEnabled(supported && Build.VERSION.SDK_INT >= 33 && !busy);
        if (busy || state == EmbeddedHelperManager.State.READY) {
            feedback.setVisibility(View.GONE);
            settingsFeedback.setVisibility(View.GONE);
            pairingSettingsFeedback.setVisibility(View.GONE);
        } else if (needsCode && (state == EmbeddedHelperManager.State.ERROR || state == EmbeddedHelperManager.State.WIFI_REQUIRED
                || state == EmbeddedHelperManager.State.DEBUGGING_REQUIRED || state == EmbeddedHelperManager.State.PAIRING_REQUIRED)) {
            showFeedback(feedback, HelperStatusText.explanation(state, paired));
        }
        if (becameReady) scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
    }

    private void submitPairing() {
        if (!pair.isEnabled()) return;
        codeError.setVisibility(View.GONE);
        portError.setVisibility(View.GONE);
        feedback.setVisibility(View.GONE);
        String enteredCode = code.getText().toString();
        if (!enteredCode.matches("[0-9]{6}")) {
            showFeedback(codeError, "설정에 표시된 6자리 숫자를 입력하세요.");
            code.requestFocus();
            return;
        }
        String enteredPort = port.getText().toString().trim();
        int pairingPort = 0;
        if (!enteredPort.isEmpty()) {
            if (!enteredPort.matches("[0-9]{1,5}")) {
                showFeedback(portError, "포트는 비워두거나 1부터 65535 사이의 숫자를 입력하세요.");
                port.requestFocus();
                return;
            }
            pairingPort = Integer.parseInt(enteredPort);
            if (pairingPort < 1 || pairingPort > 65535) {
                showFeedback(portError, "포트는 1부터 65535 사이의 숫자입니다. 자동으로 찾으려면 비워두세요.");
                port.requestFocus();
                return;
            }
        }
        pendingState = EmbeddedHelperManager.State.PAIRING;
        code.getText().clear();
        render(manager.state());
        manager.pair(pairingPort, enteredCode, automaticRecovery.isChecked());
    }

    private void openSettings() {
        code.getText().clear();
        settingsFeedback.setVisibility(View.GONE);
        pairingSettingsFeedback.setVisibility(View.GONE);
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException error) {
            String message = "개발자 옵션을 열지 못했습니다. Android 설정에서 개발자 옵션 → 무선 디버깅을 열어 주세요.";
            showFeedback(pairingGroup.getVisibility() == View.VISIBLE ? pairingSettingsFeedback : settingsFeedback, message);
        }
    }

    private void clearErrorOnEdit(EditText field, final TextView error) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { error.setVisibility(View.GONE); }
            @Override public void afterTextChanged(Editable text) { }
        });
    }

    private EditText field(LinearLayout parent, String title, int inputType) {
        TextView label = label(parent, title, R.dimen.continuity_type_ui_body);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, size(R.dimen.continuity_space_4), 0, size(R.dimen.continuity_space_1));
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        field.setMinHeight(size(R.dimen.continuity_control_min_height));
        field.setContentDescription(title);
        parent.addView(field, fullWidth());
        return field;
    }

    private LinearLayout group(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        parent.addView(group, fullWidth());
        return group;
    }

    private LinearLayout surface(LinearLayout parent) {
        LinearLayout surface = group(parent);
        int inset = size(R.dimen.continuity_space_4);
        surface.setPadding(inset, inset, inset, inset);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(android.R.attr.colorBackgroundFloating));
        background.setCornerRadius(size(R.dimen.continuity_surface_radius));
        surface.setBackground(background);
        return surface;
    }

    private void section(LinearLayout parent, String title) {
        TextView label = label(parent, title, R.dimen.continuity_type_ui_body);
        label.setTextAppearance(R.style.TextAppearance_ContinuityBridge_Section);
        label.setAccessibilityHeading(true);
        label.setPadding(0, size(R.dimen.continuity_space_6), 0, size(R.dimen.continuity_space_2));
    }

    private TextView label(LinearLayout parent, String text, int textSize) {
        TextView label = new TextView(this);
        label.setTextLocale(Locale.KOREAN);
        label.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        if (Build.VERSION.SDK_INT >= 33) label.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        KoreanUiText.set(label, text);
        textColor(label, android.R.attr.textColorPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(textSize));
        label.setLineSpacing(size(R.dimen.continuity_space_1), 1);
        label.setPadding(0, size(R.dimen.continuity_space_2), 0, size(R.dimen.continuity_space_1));
        parent.addView(label, fullWidth());
        return label;
    }

    private TextView caption(LinearLayout parent, String text) {
        TextView caption = label(parent, text, R.dimen.continuity_type_ui_caption);
        textColor(caption, android.R.attr.textColorSecondary);
        return caption;
    }

    private TextView feedback(LinearLayout parent) {
        TextView feedback = label(parent, "", R.dimen.continuity_type_ui_body);
        feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        textColor(feedback, android.R.attr.colorError);
        return feedback;
    }

    private Button button(LinearLayout parent, String text, boolean primary) {
        Button button = primary ? new Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Colored)
                : new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMinWidth(size(R.dimen.continuity_control_min_height));
        button.setMinHeight(size(R.dimen.continuity_control_min_height));
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.continuity_type_ui_body));
        parent.addView(button, fullWidth());
        return button;
    }

    private void showFeedback(TextView view, String text) { setText(view, text); view.setVisibility(View.VISIBLE); }
    private void setText(TextView view, String text) { if (!TextUtils.equals(view.getText(), text)) KoreanUiText.set(view, text); }
    private void textColor(TextView view, int attribute) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId == 0) view.setTextColor(value.data);
            else view.setTextColor(getColorStateList(value.resourceId));
        }
    }
    private int color(int attribute) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId == 0 ? value.data : getColor(value.resourceId);
    }
    private int size(int resource) { return getResources().getDimensionPixelSize(resource); }
    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}

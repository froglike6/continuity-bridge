package android.content;

public final class Intent {
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    private final String action;
    public Intent(String action) { this.action = action; }
    public Intent(Context context, Class<?> type) { action = null; }
    public String getAction() { return action; }
}

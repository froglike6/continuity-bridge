package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.Comparator;

final class NotificationAppCatalog {
    static final class Entry {
        final String packageName, label;
        final boolean system;
        final Drawable icon;

        Entry(ApplicationInfo info, PackageManager manager) {
            packageName = info.packageName;
            label = info.loadLabel(manager).toString();
            system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && manager.getLaunchIntentForPackage(packageName) == null;
            icon = info.loadIcon(manager);
        }

        boolean matches(String query) {
            return label.toLowerCase(Locale.ROOT).contains(query) || packageName.toLowerCase(Locale.ROOT).contains(query);
        }
    }

    static List<Entry> load(Context context) {
        PackageManager manager = context.getPackageManager();
        List<Entry> apps = new ArrayList<>();
        for (ApplicationInfo info : manager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (!context.getPackageName().equals(info.packageName)) apps.add(new Entry(info, manager));
        }
        Collator collator = Collator.getInstance(Locale.KOREAN);
        Collections.sort(apps, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                int labelOrder = collator.compare(left.label, right.label);
                return labelOrder == 0 ? left.packageName.compareTo(right.packageName) : labelOrder;
            }
        });
        return apps;
    }

    private NotificationAppCatalog() { }
}

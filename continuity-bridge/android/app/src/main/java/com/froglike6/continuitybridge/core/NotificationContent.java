package com.froglike6.continuitybridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NotificationContent {
    private static final Pattern PERCENT = Pattern.compile("(?<![\\p{L}\\p{N}.,+\\-])([0-9]{1,3})\\s*[%％](?![\\p{L}\\p{N}])");
    private static final Pattern TASK_CUE = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])(?:download(?:ing|ed|s)?|upload(?:ing|ed|s)?"
            + "|install(?:ing|ed|ation|s)?|updat(?:e|ed|es|ing)|transfer(?:ring|red|s)?)(?![\\p{L}\\p{N}])"
            + "|다운로드|업로드|설치|업데이트|전송");
    private static final Pattern HARDWARE_STATUS = Pattern.compile("(?im)^\\s*(?:(?:batter(?:y|ies)"
            + "|(?:(?:fast|super fast|wireless|usb)\\s+)*charg(?:e|ed|ing)|storage|volume|(?:system|device)\\s+status)"
            + "(?![\\p{L}\\p{N}_]|\\.[\\p{L}\\p{N}])|(?:배터리|(?:(?:초고속|고속|무선|유선)\\s*)*충전"
            + "|(?:남은\\s*)?저장\\s*공간|볼륨|음량|(?:시스템|기기)\\s*상태)(?!\\.[\\p{L}\\p{N}]))");
    private NotificationContent() { }

    static boolean hasText(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++)
            if (!Character.isWhitespace(value.charAt(i)) && !Character.isSpaceChar(value.charAt(i))) return true;
        return false;
    }

    static String body(String text, String bigText, String messagingText, String[] lines) {
        if (hasText(bigText)) return bigText;
        if (hasText(messagingText)) return messagingText;
        String lineText = joinLines(lines);
        return hasText(lineText) ? lineText : text == null ? "" : text;
    }

    static String joinLines(String[] lines) {
        if (lines == null) return "";
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (!hasText(line)) continue;
            if (result.length() > 0) result.append('\n');
            int remaining = 65_536 - result.length();
            if (remaining <= 0) break;
            result.append(line, 0, Math.min(line.length(), remaining));
            if (result.length() >= 65_536) break;
        }
        return Utf8.truncate(result.toString(), 65_536);
    }

    static boolean isRedacted(String frameworkMarker, String text, String bigText, String messagingText) {
        if (!hasText(frameworkMarker)) return false;
        String marker = frameworkMarker.trim();
        return matches(marker, text) || matches(marker, bigText) || matches(marker, messagingText);
    }

    static NotificationProgress progress(Integer sourceValue, Integer sourceMax, boolean indeterminate,
                                         boolean ongoing, String title, String body) {
        int value = sourceValue == null ? 0 : sourceValue;
        int max = sourceMax == null ? 0 : sourceMax;
        if (value < 0 || max < 0 || value > max) return null;
        String content = (title == null ? "" : title) + "\n" + (body == null ? "" : body);
        if (HARDWARE_STATUS.matcher(content).find()) return null;
        if (indeterminate || max > 0) return new NotificationProgress(value, max, indeterminate);
        if (!ongoing || !TASK_CUE.matcher(content).find()) return null;
        Matcher matcher = PERCENT.matcher(content);
        Integer percent = null;
        while (matcher.find()) {
            int current = Integer.parseInt(matcher.group(1));
            if (current > 100 || (percent != null && percent != current)) return null;
            percent = current;
        }
        return percent == null ? null : new NotificationProgress(percent, 100, false);
    }

    private static boolean matches(String marker, String text) {
        return text != null && marker.equals(text.trim());
    }
}

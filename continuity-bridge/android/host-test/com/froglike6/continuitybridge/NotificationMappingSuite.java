package com.froglike6.continuitybridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public final class NotificationMappingSuite {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=";
    private static int cases;
    private NotificationMappingSuite() { }

    public static void main(String[] args) throws Exception {
        emptyBigTextPreservesBody();
        contentFallbacks();
        progressPreservesSourceSemantics();
        progressTextRequiresUnambiguousOngoingPercent();
        progressTextRequiresTaskCue();
        progressRejectsHardwareStatus();
        progressPreservesTaskFilenames();
        redactionRequiresDeliveredMarker();
        optionalFieldsPreserveLegacyCallers();
        contentlessGroupSummaryFilter();
        durableNotificationMetadata();
        System.out.println("NOTIFICATION_MAPPING_OK cases=" + cases);
    }

    private static void contentlessGroupSummaryFilter() {
        NotificationFields blank = NotificationMapper.map("key", "pkg", "App", "", "", null, 1);
        check(NotificationMapper.excludeContentlessGroupSummary(true, blank), "contentless group summary should be excluded");
        check(!NotificationMapper.excludeContentlessGroupSummary(false, blank), "blank child notification was excluded");
        check(NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", " \n", "\t", null, 1)), "whitespace-only summary was retained");
        check(!NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "Summary", "", null, 1)), "meaningful summary title was excluded");
        check(!NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "", "Summary body", null, 1)), "meaningful summary body was excluded");
        check(!NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "", "", null, 1, null,
                        new NotificationProgress(20, 100, false), false, false, null)), "progress-only summary was excluded");
        check(NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "", "", null, 1, null, null, true, false, null)), "empty ongoing group summary was retained");
        check(!NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "", "", null, 1, null, null, false, true, null)), "redaction state was excluded");
        check(NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "", "", null, 1, null, null, false, false, "err")), "empty categorized summary was retained");
        check(!NotificationMapper.excludeContentlessGroupSummary(false,
                NotificationMapper.map("key", "pkg", "App", "", "", null, 1, null, null, true, false, "progress")), "blank ongoing child was excluded");
        check(!NotificationMapper.excludeContentlessGroupSummary(true,
                NotificationMapper.map("key", "pkg", "App", "", "", null, 1, null,
                        new NotificationProgress(20, 100, false), true, false, "progress")), "real ongoing progress summary was excluded");
    }

    private static void durableNotificationMetadata() throws Exception {
        Path directory = Files.createTempDirectory("notification-metadata-");
        try {
            FileBridgeStateStore store = FileBridgeStateStore.create(directory.resolve("state"), TestStateCipher.create(), "device", "epoch", 10, 10);
            DurableOutbox outbox = new DurableOutbox(store, new SequenceIds("notification"), new ObservationWindow(1_000, 8));
            NotificationFields rich = NotificationMapper.map("key", "pkg", "App", "Download", "37%", null, 1,
                    PNG, new NotificationProgress(37, 100, false), true, false, "progress");
            check(outbox.enqueueNotification(rich, 1), "rich notification enqueue failed");
            Map<String, Object> payload = store.load().outbox().get(0).wirePayload();
            check(PNG.equals(payload.get("iconPngBase64")) && Boolean.TRUE.equals(payload.get("isOngoing"))
                    && Boolean.FALSE.equals(payload.get("isRedacted")) && "progress".equals(payload.get("category")), "typed metadata did not survive persistence");
            Object progress = payload.get("progress");
            check(progress instanceof Map<?, ?> && Long.valueOf(37).equals(((Map<?, ?>) progress).get("value"))
                    && Long.valueOf(100).equals(((Map<?, ?>) progress).get("max"))
                    && Boolean.FALSE.equals(((Map<?, ?>) progress).get("indeterminate")), "progress shape changed after persistence");
            String body = repeated('b', 65_536);
            NotificationFields nearLimit = NotificationMapper.map(repeated('k', 3_970), "pkg", repeated('a', 4_096),
                    repeated('t', 8_192), body, null, 2, PNG, null, null, null, null);
            check(outbox.enqueueNotification(nearLimit, 2), "icon overflow dropped entire notification");
            Map<String, Object> bounded = store.load().outbox().get(1).wirePayload();
            check(!bounded.containsKey("iconPngBase64") && body.equals(bounded.get("body")), "icon overflow changed source body");
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
            }
        }
    }

    private static String repeated(char value, int count) {
        char[] result = new char[count]; Arrays.fill(result, value); return new String(result);
    }

    private static void contentFallbacks() {
        check("expanded".equals(NotificationContent.body("summary", "expanded", "Alice: hi", new String[] { "line" })), "expanded body lost");
        check("Alice: hi".equals(NotificationContent.body("summary", "", "Alice: hi", new String[] { "line" })), "messaging history lost");
        check("one\ntwo".equals(NotificationContent.body("summary", null, null, new String[] { "one", " ", "two" })), "inbox lines lost");
        check("summary".equals(NotificationContent.body("summary", null, null, null)), "normal text lost");
        check("".equals(NotificationContent.body(null, null, null, null)), "missing body should be empty");
    }

    private static void progressPreservesSourceSemantics() {
        NotificationProgress progress = NotificationContent.progress(37, 200, false, true, "Download", "18%");
        check(progress != null && progress.getValue() == 37 && progress.getMax() == 200 && !progress.isIndeterminate(), "source extras should win");
        NotificationProgress complete = NotificationContent.progress(100, 100, false, false, "Finished", "Saved");
        check(complete != null && complete.getValue() == complete.getMax(), "completion progress lost when ongoing clears");
        NotificationProgress unknown = NotificationContent.progress(0, 0, true, true, "Waiting", "");
        check(unknown != null && unknown.isIndeterminate() && unknown.getMax() == 0, "indeterminate progress lost");
        check(NotificationContent.progress(-1, 100, false, true, "", "40%") == null, "negative extras accepted");
        check(NotificationContent.progress(101, 100, false, true, "", "40%") == null, "overflow extras accepted");
    }

    private static void progressTextRequiresUnambiguousOngoingPercent() {
        NotificationProgress progress = NotificationContent.progress(null, null, false, true, "Downloading", "37%");
        check(progress != null && progress.getValue() == 37 && progress.getMax() == 100, "ongoing percent missing");
        check(NotificationContent.progress(0, 0, false, true, "Downloading 37%", "37% complete").getValue() == 37, "matching percents conflict");
        check(NotificationContent.progress(null, null, false, false, "Discount", "37%") == null, "ordinary percentage treated as progress");
        for (String body : new String[] { "-1%", "12.5%", "101%", "10% then 20%", "abc37%", "37%abc" })
            check(NotificationContent.progress(null, null, false, true, "Download", body) == null, "ambiguous percentage accepted: " + body);
    }

    private static void progressTextRequiresTaskCue() {
        // Given: persistent status or promotional text contains a percentage without a task cue.
        String[][] statuses = { { "충전 중", "배터리 67%" }, { "Battery charging", "67%" },
                { "Storage", "70% used" }, { "Volume", "70%" }, { "Discount", "37%" },
                { "", "37%" }, { "Progress", "37%" }, { "Downloadable offers", "37% off" } };
        for (String[] status : statuses) {
            // When: only text is available to infer progress.
            NotificationProgress progress = NotificationContent.progress(null, null, false, true, status[0], status[1]);
            // Then: a percentage alone is not a task progress bar.
            check(progress == null, "non-task percentage accepted: " + status[0]);
        }
        // Given: ongoing work explicitly names an English or Korean task.
        for (String task : new String[] { "Download", "Downloading", "UPLOAD", "Installing", "Installation",
                "Updating", "Transfer", "Transferring", "다운로드 중", "업로드 중", "설치 중", "업데이트 중", "파일 전송 중" }) {
            // When: its body contains one unambiguous percentage.
            NotificationProgress progress = NotificationContent.progress(null, null, false, true, task, "37%");
            // Then: the original progress remains available.
            check(progress != null && progress.getValue() == 37 && progress.getMax() == 100, "task percentage lost: " + task);
        }
        NotificationProgress bodyCue = NotificationContent.progress(null, null, false, true, "file.zip", "Downloading 37％");
        check(bodyCue != null && bodyCue.getValue() == 37, "task cue in body was ignored");
    }

    private static void progressRejectsHardwareStatus() {
        // Given: hardware status notifications also use Android's progress extras.
        for (String status : new String[] { "Battery charging", "CHARGE STATUS", "Storage used", "Volume",
                "System status", "Device status", "배터리", "고속 무선 충전 중", "저장 공간", "저장공간", "볼륨", "음량", "기기 상태" }) {
            // When: valid source extras accompany hardware status text.
            NotificationProgress progress = NotificationContent.progress(67, 100, false, true, status, "67%");
            // Then: status values never become task progress bars.
            check(progress == null, "hardware status extras accepted: " + status);
        }
        check(NotificationContent.progress(0, 0, true, true, "충전 준비 중", "") == null,
                "indeterminate charging status accepted");
        check(NotificationContent.progress(100, 100, false, false, "Battery", "Fully charged") == null,
                "charged battery accepted as task completion");
        check(NotificationContent.progress(null, null, false, true, "Battery status update", "67%") == null,
                "status update mistaken for an update task");
        check(NotificationContent.progress(null, null, false, true, "업데이트", "배터리 잔량 67%") == null,
                "battery percentage in task body accepted");
    }

    private static void progressPreservesTaskFilenames() {
        String[][] tasks = { { "Downloading battery.pdf", "37%" }, { "Uploading storage.zip", "37%" },
                { "Download", "battery.pdf 37%" }, { "volume.mp4", "Uploading 37%" },
                { "파일 다운로드", "배터리.pdf 37%" }, { "Downloading", "Saved to device storage: 37%" } };
        for (String[] task : tasks) {
            NotificationProgress inferred = NotificationContent.progress(null, null, false, true, task[0], task[1]);
            check(inferred != null && inferred.getValue() == 37, "task filename became hardware status: " + task[0]);
            NotificationProgress explicit = NotificationContent.progress(74, 200, false, true, task[0], task[1]);
            check(explicit != null && explicit.getValue() == 74 && explicit.getMax() == 200,
                    "task source progress lost for filename: " + task[0]);
        }
    }

    private static void redactionRequiresDeliveredMarker() {
        String marker = "민감한 알림 내용 숨김";
        check(NotificationContent.isRedacted(marker, marker, "stale expanded secret", null), "delivered framework marker lost");
        check(NotificationContent.isRedacted(marker, null, null, marker), "redacted MessagingStyle body missed");
        check(!NotificationContent.isRedacted(marker, "실제 메시지", null, null), "ordinary private body assumed redacted");
        check(!NotificationContent.isRedacted(null, "system notification", null, null), "generic title assumed redacted");
        check(!NotificationContent.isRedacted(marker, "문장 속 " + marker, null, null), "partial marker match overclassified body");
    }

    private static void optionalFieldsPreserveLegacyCallers() {
        NotificationFields legacy = new NotificationFields("key", "pkg", "App", "title", "body", 1);
        check(legacy.getIconPngBase64() == null && legacy.getProgress() == null && legacy.getIsOngoing() == null
                && legacy.getIsRedacted() == null && legacy.getCategory() == null, "legacy fields should omit new metadata");
        NotificationProgress progress = new NotificationProgress(50, 100, false);
        NotificationFields rich = NotificationMapper.map("key", "pkg", "App", "title", "body", "", 1,
                "icon", progress, true, false, "progress");
        check("icon".equals(rich.getIconPngBase64()) && rich.getProgress() == progress && Boolean.TRUE.equals(rich.getIsOngoing())
                && Boolean.FALSE.equals(rich.getIsRedacted()) && "progress".equals(rich.getCategory()), "optional metadata lost");
    }

    private static void emptyBigTextPreservesBody() {
        // Given: a source app supplies an empty expanded body and meaningful normal text.
        String[] emptyValues = { "", "  ", "\n\t" };
        for (String bigText : emptyValues) {
            // When: the notification is mapped for transport.
            NotificationFields fields = NotificationMapper.map("key", "pkg", "App", "title", "body", bigText, 7);
            // Then: the delivered normal body survives.
            check("body".equals(fields.body()), "empty bigText replaced meaningful text");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        cases++;
    }
}

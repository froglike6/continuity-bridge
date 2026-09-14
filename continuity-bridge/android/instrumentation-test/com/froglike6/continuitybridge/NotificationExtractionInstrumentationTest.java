package com.froglike6.continuitybridge;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Icon;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.InstrumentationTestCase;
import android.util.Base64;
import java.util.Random;

public final class NotificationExtractionInstrumentationTest extends InstrumentationTestCase {
    public void testEmptyExpandedBodyPreservesNormalText() {
        Notification notification = builder().setContentTitle("Title").setContentText("Normal body").build();
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "");
        assertEquals("Normal body", extract(notification).body());
    }

    public void testMessagingStylePreservesConversationAndMessages() {
        Person alice = new Person.Builder().setName("Alice").build();
        Person bob = new Person.Builder().setName("Bob").build();
        Notification notification = builder().setStyle(new Notification.MessagingStyle(new Person.Builder().setName("Me").build())
                .setConversationTitle("Research").setGroupConversation(true)
                .addMessage("First message", 1, alice).addMessage("Second message", 2, bob)).build();
        assertEquals("Research", notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        NotificationFields fields = extract(notification);
        assertEquals("Research", fields.title());
        assertEquals("Alice: First message\nBob: Second message", fields.body());
        assertEquals(Boolean.FALSE, fields.getIsRedacted());
    }

    public void testMessagingStyleWithoutConversationTitleKeepsSenderTitle() {
        Notification notification = builder().setStyle(new Notification.MessagingStyle(new Person.Builder().setName("Me").build())
                .addMessage("Message", 1, new Person.Builder().setName("Alice").build())).build();
        assertEquals("Alice", extract(notification).title());
    }

    public void testRedactedMessagingDoesNotPromoteStaleConversationTitle() {
        String marker = NotificationExtractor.redactionMarker(context().getResources());
        assertNotNull(marker);
        Notification notification = builder().setStyle(new Notification.MessagingStyle(new Person.Builder().setName("Me").build())
                .setConversationTitle("Private room").setGroupConversation(true)
                .addMessage("Old message", 1, new Person.Builder().setName("Alice").build())).build();
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "Source app");
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, marker);
        NotificationFields fields = extract(notification);
        assertEquals("Source app", fields.title());
        assertEquals(marker, fields.body());
        assertEquals(Boolean.TRUE, fields.getIsRedacted());
    }

    public void testInboxStylePreservesDeliveredLines() {
        Notification notification = builder().setContentText("Two updates")
                .setStyle(new Notification.InboxStyle().addLine("First line").addLine("Second line")).build();
        assertEquals("First line\nSecond line", extract(notification).body());
    }

    public void testPrivateVisibilityDoesNotMarkReadableBodyRedacted() {
        Notification notification = builder().setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentTitle("Private note").setContentText("Actually delivered message").build();
        NotificationFields fields = extract(notification);
        assertEquals("Actually delivered message", fields.body());
        assertEquals(Boolean.FALSE, fields.getIsRedacted());
    }

    public void testContentlessGroupSummaryIsExcluded() {
        Notification notification = builder().setGroup("summary-test").setGroupSummary(true).build();
        assertTrue(excludedSummary(notification));
    }

    public void testOngoingContentlessGroupSummaryIsExcluded() {
        Notification notification = builder().setGroup("summary-test").setGroupSummary(true).setOngoing(true).build();
        assertTrue(excludedSummary(notification));
    }

    public void testSummaryWithInboxLinesIsPreserved() {
        Notification notification = builder().setGroup("summary-test").setGroupSummary(true)
                .setStyle(new Notification.InboxStyle().addLine("First item").addLine("Second item")).build();
        assertFalse(excludedSummary(notification));
    }

    public void testBlankChildNotificationIsPreserved() {
        Notification notification = builder().setGroup("summary-test").setGroupSummary(false).build();
        assertFalse(excludedSummary(notification));
    }

    public void testProgressOnlySummaryIsPreserved() {
        Notification notification = builder().setGroup("summary-test").setGroupSummary(true).setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS).setProgress(100, 35, false).build();
        assertFalse(excludedSummary(notification));
    }

    public void testFrameworkRedactionDoesNotExposeStaleExpandedText() {
        String marker = NotificationExtractor.redactionMarker(context().getResources());
        assertNotNull("Framework redaction string unavailable on this Android version", marker);
        Notification notification = builder().setContentTitle("App").setContentText(marker)
                .setStyle(new Notification.BigTextStyle().bigText("Stale expanded secret")).build();
        NotificationFields fields = extract(notification);
        assertEquals(Boolean.TRUE, fields.getIsRedacted());
        assertEquals(marker, fields.body());
    }

    public void testFrameworkRedactedMessagingStyleIsDetected() {
        String marker = NotificationExtractor.redactionMarker(context().getResources());
        assertNotNull("Framework redaction string unavailable on this Android version", marker);
        Person empty = new Person.Builder().setName("").build();
        Notification notification = builder().setStyle(new Notification.MessagingStyle(empty).addMessage(marker, 1, empty)).build();
        NotificationFields fields = extract(notification);
        assertEquals(Boolean.TRUE, fields.getIsRedacted());
        assertEquals(marker, fields.body());
    }

    public void testProgressSurvivesCompletionAndIndeterminateState() {
        NotificationFields progress = extract(builder().setProgress(250, 90, false).setOngoing(true).build());
        assertEquals(90, progress.getProgress().getValue());
        assertEquals(250, progress.getProgress().getMax());
        assertEquals(Boolean.TRUE, progress.getIsOngoing());
        NotificationFields complete = extract(builder().setProgress(250, 250, false).setOngoing(false).build());
        assertEquals(250, complete.getProgress().getValue());
        assertEquals(Boolean.FALSE, complete.getIsOngoing());
        assertTrue(extract(builder().setProgress(0, 0, true).setOngoing(true).build()).getProgress().isIndeterminate());
    }

    public void testChargingPercentagePreservesStatusWithoutProgress() {
        Notification notification = builder().setContentTitle("Charging").setContentText("68%")
                .setCategory(Notification.CATEGORY_STATUS).setOngoing(true).build();

        NotificationFields fields = extract(notification);

        assertNull(fields.getProgress());
        assertEquals(Notification.CATEGORY_STATUS, fields.getCategory());
        assertEquals(Boolean.TRUE, fields.getIsOngoing());
    }

    public void testChargingProgressBarDoesNotBecomeTaskProgress() {
        Notification notification = builder().setContentTitle("Battery charging").setContentText("68%")
                .setCategory(Notification.CATEGORY_STATUS).setProgress(100, 68, false).setOngoing(true).build();

        NotificationFields fields = extract(notification);

        assertNull(fields.getProgress());
        assertEquals(Notification.CATEGORY_STATUS, fields.getCategory());
    }

    public void testKoreanChargingPercentageDoesNotBecomeTaskProgress() {
        Notification notification = builder().setContentTitle("충전 중").setContentText("68%")
                .setCategory(Notification.CATEGORY_STATUS).setOngoing(true).build();

        NotificationFields fields = extract(notification);

        assertNull(fields.getProgress());
    }

    public void testDownloadPercentageRetainsTaskProgress() {
        Notification notification = builder().setContentTitle("Downloading file").setContentText("37%")
                .setCategory(Notification.CATEGORY_PROGRESS).setOngoing(true).build();

        NotificationFields fields = extract(notification);

        assertNotNull(fields.getProgress());
        assertEquals(37, fields.getProgress().getValue());
        assertEquals(100, fields.getProgress().getMax());
        assertFalse(fields.getProgress().isIndeterminate());
        assertEquals(Notification.CATEGORY_PROGRESS, fields.getCategory());
    }

    public void testExplicitDownloadProgressRetainsTaskProgress() {
        Notification notification = builder().setContentTitle("Downloading file").setContentText("Receiving file")
                .setCategory(Notification.CATEGORY_PROGRESS).setProgress(250, 90, false).setOngoing(true).build();

        NotificationFields fields = extract(notification);

        assertNotNull(fields.getProgress());
        assertEquals(90, fields.getProgress().getValue());
        assertEquals(250, fields.getProgress().getMax());
        assertFalse(fields.getProgress().isIndeterminate());
    }

    public void testSourceApplicationIdentityIgnoresSenderAvatar() {
        Notification plain = builder().setContentTitle("System notification").setContentText("body").build();
        Bitmap avatar = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        avatar.eraseColor(Color.MAGENTA);
        Notification withAvatar = builder().setContentTitle("Different sender").setLargeIcon(Icon.createWithBitmap(avatar)).build();
        NotificationFields source = extract(plain);
        NotificationFields other = extract(withAvatar);
        assertEquals(context().getPackageName(), source.packageName());
        assertEquals(context().getApplicationInfo().loadLabel(context().getPackageManager()).toString(), source.appLabel());
        assertNotNull(source.getIconPngBase64());
        assertEquals(source.getIconPngBase64(), other.getIconPngBase64());
        byte[] icon = Base64.decode(source.getIconPngBase64(), Base64.DEFAULT);
        Bitmap decoded = BitmapFactory.decodeByteArray(icon, 0, icon.length);
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
        assertTrue(icon.length <= 12_288 && source.getIconPngBase64().length() <= 16_384);
        decoded.recycle(); avatar.recycle();
    }

    public void testIconRasterizationProducesBoundedPng() {
        String encoded = NotificationAppIcon.encode(new ColorDrawable(Color.GREEN));
        byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertEquals(Color.GREEN, decoded.getPixel(32, 32));
        assertEquals(64, decoded.getWidth());
        assertTrue(bytes.length <= 12_288);
        decoded.recycle();
    }

    public void testHighEntropyIconShrinksToFitTheWireBudget() {
        Bitmap source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[64 * 64];
        Random random = new Random(71);
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
        source.setPixels(pixels, 0, 64, 0, 0, 64, 64);
        String encoded = NotificationAppIcon.encode(new BitmapDrawable(context().getResources(), source));
        byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertTrue(decoded.getWidth() < 64 && decoded.getWidth() > 0);
        assertTrue(bytes.length <= 12_288 && encoded.length() <= 16_384);
        decoded.recycle(); source.recycle();
    }

    private Context context() { return getInstrumentation().getTargetContext(); }
    private boolean excludedSummary(Notification notification) {
        return NotificationMapper.excludeContentlessGroupSummary((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0, extract(notification));
    }
    private Notification.Builder builder() {
        return new Notification.Builder(context(), "notification_extraction_test").setSmallIcon(android.R.drawable.stat_notify_more);
    }
    private NotificationFields extract(Notification notification) {
        StatusBarNotification source = new StatusBarNotification(context().getPackageName(), context().getPackageName(), 51,
                "notification-extraction-test", Process.myUid(), Process.myPid(), 0, notification,
                UserHandle.getUserHandleForUid(Process.myUid()), 7);
        return new NotificationExtractor(context()).extract(source);
    }
}

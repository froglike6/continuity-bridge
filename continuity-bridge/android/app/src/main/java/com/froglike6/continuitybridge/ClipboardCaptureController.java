package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.Context;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class ClipboardCaptureController {
    private final Context context;
    private final DurableOutbox outbox;
    private final ShizukuClipboardClient client;
    private final String epoch = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean stopping;
    private final ThreadPoolExecutor captures = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(1), new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "continuity-clipboard-capture");
                    thread.setDaemon(true);
                    return thread;
                }
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ShizukuClipboardClient.Listener listener = new ShizukuClipboardClient.Listener() {
        @Override public void onClip(ClipData clip) {
            capture(clip, "shizuku:" + epoch + ":" + sequence.incrementAndGet());
        }

        @Override public void onState(ShizukuClipboardClient.State state) {
            if (stopping) return;
            MetadataLog.clipboardMonitor("shizuku_" + state.name());
            new ConfigStore(context).clipboardCapability(capability(state));
        }
    };

    ClipboardCaptureController(Context context, DurableOutbox outbox) {
        this.context = context.getApplicationContext();
        this.outbox = outbox;
        client = ShizukuClipboardClient.get(this.context);
    }

    void start() {
        stopping = false;
        client.start(listener);
    }

    void stop() {
        stopping = true;
        client.stop(listener);
        captures.shutdownNow();
    }

    CaptureResult capture(ClipData clip, String identity) {
        if (stopping) return CaptureResult.UNAVAILABLE;
        final ClipboardObservation observation = ClipboardObservation.fromShizuku(clip, identity);
        if (observation == null) return CaptureResult.INVALID;
        try {
            captures.execute(new Runnable() {
                @Override public void run() { capture(observation); }
            });
            return CaptureResult.PENDING;
        } catch (RejectedExecutionException stopped) {
            if (!stopping) reportFailure("클립보드 저장 대기 중");
            return CaptureResult.UNAVAILABLE;
        }
    }

    private void capture(ClipboardObservation observation) {
        if (stopping || Thread.currentThread().isInterrupted()) return;
        try {
            ClipboardContent content = observation.content(context);
            if (stopping || Thread.currentThread().isInterrupted()) return;
            CaptureResult result = outbox.captureContent(content, observation.marker, observation.identity,
                    observation.capturedAtMs, observation.remote);
            MetadataLog.clipboardMonitor("shizuku_capture_" + result.name());
            if (stopping || client.getState() != ShizukuClipboardClient.State.READY) return;
            if (result == CaptureResult.ENQUEUED) new ConfigStore(context).clipboardCapability(
                    content.isImage() ? "이미지 복사 공유 준비됨" : "복사 공유 준비됨");
            else if (result == CaptureResult.UNAVAILABLE) reportFailure("클립보드 저장 실패");
        } catch (IOException | RuntimeException error) {
            MetadataLog.clipboardMonitor("shizuku_capture_failed");
            reportFailure(observation.isImage() ? ClipboardImageReader.status(error) : "복사 내용을 읽지 못했어요");
        }
    }

    private void reportFailure(String message) {
        if (!stopping && client.getState() == ShizukuClipboardClient.State.READY)
            new ConfigStore(context).clipboardCapability(message);
    }

    private static String capability(ShizukuClipboardClient.State state) {
        switch (state) {
            case MISSING: return "클립보드 도우미는 Android 11 이상이 필요합니다";
            case NOT_RUNNING: return "클립보드 도우미 연결을 확인해 주세요";
            case PERMISSION_REQUIRED: return "클립보드 도우미 최초 연결이 필요합니다";
            case CONNECTING: return "클립보드 도우미 연결 중";
            case READY: return "복사 공유 준비됨";
            case ERROR: return "클립보드 도우미를 다시 연결해 주세요";
            case STOPPED: return "복사 공유 중지됨";
            default: throw new IllegalStateException("unknown_shizuku_state");
        }
    }
}

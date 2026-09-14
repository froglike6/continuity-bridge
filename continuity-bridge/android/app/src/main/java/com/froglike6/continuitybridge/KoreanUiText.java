package com.froglike6.continuitybridge;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KoreanUiText {
    private static final Pattern WORD = Pattern.compile("\\S*[가-힣]\\S*");

    private KoreanUiText() { }

    static void set(TextView view, String text) {
        if (Build.VERSION.SDK_INT >= 33) {
            view.setText(text);
            return;
        }
        // Older Android has no phrase wrapping; keep the original text for accessibility.
        SpannableString display = new SpannableString(text);
        Matcher words = WORD.matcher(text);
        while (words.find()) {
            WordSpan span = new WordSpan();
            if (Build.VERSION.SDK_INT >= 30) span.setContentDescription(words.group());
            display.setSpan(span, words.start(), words.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(display);
        if (Build.VERSION.SDK_INT < 30) view.setContentDescription(text);
    }

    private static final class WordSpan extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(paint.measureText(text, start, end));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int baseline, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, baseline, paint);
        }
    }
}

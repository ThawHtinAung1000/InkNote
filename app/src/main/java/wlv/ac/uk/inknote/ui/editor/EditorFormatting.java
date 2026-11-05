package wlv.ac.uk.inknote.ui.editor;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StyleSpan;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native inline and paragraph formatting without adding text markers or a second watcher. */
public final class EditorFormatting {
    private static final int BULLET = 4;
    private static final int QUOTE = 8;

    private final EditText body;
    private final Runnable onFormattingChanged;
    private Integer pendingInline;
    private Integer pendingParagraph;
    private int caretAnchor;
    private int knownLength;
    private int typingInline;
    private boolean applying;

    public EditorFormatting(EditText body, Runnable onFormattingChanged) {
        this.body = body;
        this.onFormattingChanged = onFormattingChanged;
        onDocumentRestored();
    }

    public void toggleBold() { toggleInline(Typeface.BOLD); }
    public void toggleItalic() { toggleInline(Typeface.ITALIC); }
    public void toggleBullets() { toggleParagraph(BULLET); }
    public void toggleQuote() { toggleParagraph(QUOTE); }

    public boolean isBold() { return hasInline(Typeface.BOLD); }
    public boolean isItalic() { return hasInline(Typeface.ITALIC); }
    public boolean isBullets() { return hasParagraph(BULLET); }
    public boolean isQuote() { return hasParagraph(QUOTE); }

    /** Call after replacing the entire editor value from saved state or a database result. */
    public void onDocumentRestored() {
        pendingInline = null;
        pendingParagraph = null;
        knownLength = body.length();
        caretAnchor = selection()[1];
        typingInline = inlineAtCursor(body.getText(), caretAnchor);
    }

    /** Call for every onTextChanged notification, including count == 0 deletions. */
    public void onTextInserted(int start, int count) {
        if (applying) return;
        Editable text = body.getText();
        if (text == null) return;
        start = clamp(start, text.length());
        int end = clamp(start + Math.max(0, count), text.length());
        applying = true;
        try {
            int inheritedParagraph = pendingParagraph != null ? pendingParagraph
                    : paragraphAt(text, start);
            if (end > start) {
                int style = pendingInline != null ? pendingInline : typingInline;
                removeInline(text, start, end, Typeface.BOLD_ITALIC);
                addInline(text, start, end, style);
            }
            mergeInline(text);
            normalizeParagraphs(text, BULLET);
            normalizeParagraphs(text, QUOTE);
            if (pendingParagraph != null) {
                for (int[] range : paragraphs(text, start, end)) {
                    setParagraph(text, range, BULLET, (pendingParagraph & BULLET) != 0);
                    setParagraph(text, range, QUOTE, (pendingParagraph & QUOTE) != 0);
                }
            }
            if (end > start && containsNewline(text, start, end)) {
                // A trailing empty paragraph needs its own span to continue a list or quote.
                int[] last = paragraphs(text, end, end).get(0);
                if ((inheritedParagraph & BULLET) != 0) setParagraph(text, last, BULLET, true);
                if ((inheritedParagraph & QUOTE) != 0) setParagraph(text, last, QUOTE, true);
            }
            knownLength = text.length();
            caretAnchor = end;
            if (end == start && pendingInline == null) typingInline = inlineAtCursor(text, end);
        } finally {
            applying = false;
        }
    }

    /** Called by SelectionEditText; typing-induced changes retain an explicitly chosen mode. */
    public void onSelectionChanged() {
        if (applying || body.length() != knownLength) return;
        int[] range = selection();
        if (range[0] != range[1] || range[1] != caretAnchor) {
            pendingInline = null;
            pendingParagraph = null;
        }
        caretAnchor = range[1];
        typingInline = pendingInline != null ? pendingInline : inlineAtCursor(body.getText(), range[1]);
    }

    private void toggleInline(int style) {
        Editable text = body.getText();
        if (text == null) return;
        int[] range = selection();
        applying = true;
        try {
            if (range[0] == range[1]) {
                int current = pendingInline != null ? pendingInline : inlineAtCursor(text, range[0]);
                pendingInline = current ^ style;
                typingInline = pendingInline;
                caretAnchor = range[0];
            } else {
                boolean enabled = !inlineCovered(text, range[0], range[1], style);
                removeInline(text, range[0], range[1], style);
                if (enabled) addInline(text, range[0], range[1], style);
                pendingInline = null;
                typingInline = inlineAtCursor(text, range[1]);
            }
            mergeInline(text);
        } finally {
            applying = false;
        }
        onFormattingChanged.run();
    }

    private void toggleParagraph(int type) {
        Editable text = body.getText();
        if (text == null) return;
        int[] selection = selection();
        List<int[]> ranges = paragraphs(text, selection[0], selection[1]);
        boolean enabled = false;
        for (int[] range : ranges) {
            if (!paragraphCovered(text, range, type)) {
                enabled = true;
                break;
            }
        }
        applying = true;
        try {
            int current = pendingParagraph != null ? pendingParagraph : paragraphAt(text, selection[0]);
            for (int[] range : ranges) setParagraph(text, range, type, enabled);
            if (selection[0] == selection[1]) {
                pendingParagraph = enabled ? current | type : current & ~type;
                caretAnchor = selection[0];
            } else {
                pendingParagraph = null;
            }
        } finally {
            applying = false;
        }
        onFormattingChanged.run();
    }

    private boolean hasInline(int style) {
        Editable text = body.getText();
        if (text == null) return false;
        int[] range = selection();
        if (range[0] == range[1]) {
            int current = pendingInline != null ? pendingInline : inlineAtCursor(text, range[0]);
            return (current & style) != 0;
        }
        return inlineCovered(text, range[0], range[1], style);
    }

    private boolean hasParagraph(int type) {
        Editable text = body.getText();
        if (text == null) return false;
        int[] selection = selection();
        if (selection[0] == selection[1] && pendingParagraph != null) {
            return (pendingParagraph & type) != 0;
        }
        for (int[] range : paragraphs(text, selection[0], selection[1])) {
            if (!paragraphCovered(text, range, type)) return false;
        }
        return true;
    }

    private static int inlineAtCursor(Spanned text, int cursor) {
        if (text == null || cursor <= 0 || text.length() == 0) return Typeface.NORMAL;
        cursor = clamp(cursor, text.length());
        int style = Typeface.NORMAL;
        for (StyleSpan span : text.getSpans(cursor - 1, cursor, StyleSpan.class)) {
            if (text.getSpanStart(span) < cursor && text.getSpanEnd(span) >= cursor) {
                style |= span.getStyle() & Typeface.BOLD_ITALIC;
            }
        }
        return style;
    }

    private static boolean inlineCovered(Spanned text, int start, int end, int style) {
        List<int[]> coverage = new ArrayList<>();
        for (StyleSpan span : text.getSpans(start, end, StyleSpan.class)) {
            if ((span.getStyle() & style) != 0) {
                coverage.add(new int[]{text.getSpanStart(span), text.getSpanEnd(span)});
            }
        }
        coverage.sort(Comparator.comparingInt(range -> range[0]));
        int coveredUntil = start;
        for (int[] range : coverage) {
            if (range[0] > coveredUntil) return false;
            coveredUntil = Math.max(coveredUntil, range[1]);
            if (coveredUntil >= end) return true;
        }
        return false;
    }

    private static void removeInline(Editable text, int start, int end, int style) {
        for (StyleSpan span : text.getSpans(start, end, StyleSpan.class)) {
            int spanStart = text.getSpanStart(span);
            int spanEnd = text.getSpanEnd(span);
            int originalStyle = span.getStyle();
            if ((originalStyle & style) == 0 || spanEnd <= start || spanStart >= end) continue;
            text.removeSpan(span);
            addInline(text, spanStart, Math.min(start, spanEnd), originalStyle);
            addInline(text, Math.max(start, spanStart), Math.min(end, spanEnd), originalStyle & ~style);
            addInline(text, Math.max(end, spanStart), spanEnd, originalStyle);
        }
    }

    private static void addInline(Editable text, int start, int end, int style) {
        if (start >= 0 && end > start && end <= text.length() && style != Typeface.NORMAL) {
            text.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void mergeInline(Editable text) {
        List<int[]> ranges = new ArrayList<>();
        for (StyleSpan span : text.getSpans(0, text.length(), StyleSpan.class)) {
            int style = span.getStyle();
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            if (style < Typeface.BOLD || style > Typeface.BOLD_ITALIC || start < 0 || end <= start) continue;
            ranges.add(new int[]{style, start, end});
            text.removeSpan(span);
        }
        ranges.sort(Comparator.<int[]>comparingInt(range -> range[0])
                .thenComparingInt(range -> range[1]).thenComparingInt(range -> range[2]));
        int[] current = null;
        for (int[] range : ranges) {
            if (current != null && current[0] == range[0] && range[1] <= current[2]) {
                current[2] = Math.max(current[2], range[2]);
            } else {
                if (current != null) addInline(text, current[1], current[2], current[0]);
                current = range;
            }
        }
        if (current != null) addInline(text, current[1], current[2], current[0]);
    }

    private static int paragraphAt(Spanned text, int position) {
        int result = 0;
        int[] range = paragraphs(text, position, position).get(0);
        if (paragraphCovered(text, range, BULLET)) result |= BULLET;
        if (paragraphCovered(text, range, QUOTE)) result |= QUOTE;
        return result;
    }

    private static boolean paragraphCovered(Spanned text, int[] range, int type) {
        for (Object span : paragraphSpans(text, range[0], range[1], type)) {
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            if (start <= range[0] && end >= range[1]
                    && (range[0] != range[1] || start == range[0])) return true;
        }
        return false;
    }

    private static Object[] paragraphSpans(Spanned text, int start, int end, int type) {
        return type == BULLET ? text.getSpans(start, end, BulletSpan.class)
                : text.getSpans(start, end, QuoteSpan.class);
    }

    private static void setParagraph(Editable text, int[] range, int type, boolean enabled) {
        int start = range[0];
        int end = range[1];
        for (Object span : paragraphSpans(text, start, end, type)) {
            int previousStart = text.getSpanStart(span);
            int previousEnd = text.getSpanEnd(span);
            boolean overlaps = start == end
                    ? previousStart == start && previousEnd == end
                    : previousStart < end && previousEnd > start;
            if (!overlaps) continue;
            text.removeSpan(span);
            if (previousStart < start) addParagraph(text, previousStart, start, type);
            if (previousEnd > end) addParagraph(text, end, previousEnd, type);
        }
        if (enabled) addParagraph(text, start, end, type);
    }

    private static void normalizeParagraphs(Editable text, int type) {
        Object[] spans = paragraphSpans(text, 0, text.length(), type);
        List<int[]> ranges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object span : spans) {
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            text.removeSpan(span);
            if (start < 0 || end < start) continue;
            for (int[] range : paragraphs(text, clamp(start, text.length()), clamp(end, text.length()))) {
                if (seen.add(range[0] + ":" + range[1])) ranges.add(range);
            }
        }
        for (int[] range : ranges) addParagraph(text, range[0], range[1], type);
    }

    private static void addParagraph(Editable text, int start, int end, int type) {
        if (start < 0 || end < start || end > text.length()) return;
        if (start > 0 && text.charAt(start - 1) != '\n') return;
        if (end < text.length() && (end == 0 || text.charAt(end - 1) != '\n')) return;
        Object span = type == BULLET ? new BulletSpan() : new QuoteSpan();
        text.setSpan(span, start, end, Spanned.SPAN_PARAGRAPH);
    }

    private static List<int[]> paragraphs(CharSequence text, int start, int end) {
        start = clamp(start, text.length());
        end = clamp(Math.max(start, end), text.length());
        int paragraphStart = start;
        while (paragraphStart > 0 && text.charAt(paragraphStart - 1) != '\n') paragraphStart--;
        List<int[]> result = new ArrayList<>();
        while (true) {
            int paragraphEnd = paragraphStart;
            while (paragraphEnd < text.length() && text.charAt(paragraphEnd) != '\n') paragraphEnd++;
            if (paragraphEnd < text.length()) paragraphEnd++;
            result.add(new int[]{paragraphStart, paragraphEnd});
            if (paragraphEnd >= end || paragraphEnd == text.length()) return result;
            paragraphStart = paragraphEnd;
        }
    }

    private static boolean containsNewline(CharSequence text, int start, int end) {
        for (int index = start; index < end; index++) if (text.charAt(index) == '\n') return true;
        return false;
    }

    private int[] selection() {
        int start = clamp(body.getSelectionStart(), body.length());
        int end = clamp(body.getSelectionEnd(), body.length());
        return new int[]{Math.min(start, end), Math.max(start, end)};
    }

    private static int clamp(int value, int length) { return Math.max(0, Math.min(value, length)); }
}

package wlv.ac.uk.inknote.ui.editor;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StyleSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stores supported native formatting independently of the message's plain text. */
public final class RichTextCodec {
    private static final int VERSION = 1;
    private static final int MAX_SPANS = 2000;
    private static final int MAX_FORMAT_LENGTH = 262144;

    private RichTextCodec() { }

    public static String encode(CharSequence text) {
        if (!(text instanceof Spanned)) return "";
        Spanned styled = (Spanned) text;
        String body = text.toString();
        JSONArray entries = new JSONArray();
        try {
            for (Object span : styled.getSpans(0, styled.length(), Object.class)) {
                if (entries.length() >= MAX_SPANS) break;
                int start = styled.getSpanStart(span);
                int end = styled.getSpanEnd(span);
                if (!validRange(body, start, end)) continue;
                if (span instanceof StyleSpan && start < end) {
                    int style = ((StyleSpan) span).getStyle();
                    if ((style & Typeface.BOLD) != 0) add(entries, "bold", start, end);
                    if ((style & Typeface.ITALIC) != 0) add(entries, "italic", start, end);
                } else if (span instanceof BulletSpan && paragraphRange(body, start, end)) {
                    add(entries, "bullet", start, end);
                } else if (span instanceof QuoteSpan && paragraphRange(body, start, end)) {
                    add(entries, "quote", start, end);
                }
            }
            if (entries.length() == 0) return "";
            return new JSONObject().put("version", VERSION).put("spans", entries).toString();
        } catch (JSONException ignored) {
            return "";
        }
    }

    public static SpannableStringBuilder decode(String body, String format) {
        String plain = body == null ? "" : body;
        SpannableStringBuilder result = new SpannableStringBuilder(plain);
        if (format == null || format.isEmpty() || format.length() > MAX_FORMAT_LENGTH) return result;
        try {
            JSONObject document = new JSONObject(format);
            if (document.getInt("version") != VERSION) return result;
            JSONArray entries = document.getJSONArray("spans");
            if (entries.length() > MAX_SPANS) return result;
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                String type = entry.getString("type");
                int start = entry.getInt("start");
                int end = entry.getInt("end");
                if (!validRange(plain, start, end)) continue;
                switch (type) {
                    case "bold":
                        if (start < end) result.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    case "italic":
                        if (start < end) result.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    case "bullet":
                        if (paragraphRange(plain, start, end)) result.setSpan(new BulletSpan(), start, end,
                                Spanned.SPAN_PARAGRAPH);
                        break;
                    case "quote":
                        if (paragraphRange(plain, start, end)) result.setSpan(new QuoteSpan(), start, end,
                                Spanned.SPAN_PARAGRAPH);
                        break;
                    default:
                        // Unsupported formatting never changes the plain-text message.
                        break;
                }
            }
            return result;
        } catch (JSONException | IndexOutOfBoundsException | IllegalArgumentException ignored) {
            return new SpannableStringBuilder(plain);
        }
    }

    private static void add(JSONArray entries, String type, int start, int end) throws JSONException {
        if (entries.length() < MAX_SPANS) {
            entries.put(new JSONObject().put("type", type).put("start", start).put("end", end));
        }
    }

    private static boolean validRange(String body, int start, int end) {
        return start >= 0 && end > start && end <= body.length()
                && codePointBoundary(body, start) && codePointBoundary(body, end);
    }

    private static boolean codePointBoundary(String body, int index) {
        return index == 0 || index == body.length()
                || !Character.isHighSurrogate(body.charAt(index - 1))
                || !Character.isLowSurrogate(body.charAt(index));
    }

    private static boolean paragraphRange(String body, int start, int end) {
        return (start == 0 || body.charAt(start - 1) == '\n')
                && (end == body.length() || (end > 0 && body.charAt(end - 1) == '\n'));
    }
}

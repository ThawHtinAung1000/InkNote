package wlv.ac.uk.inknote.ui.editor;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.ContextThemeWrapper;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import wlv.ac.uk.inknote.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Native text/span checks without Activities, application preferences, or message databases. */
@RunWith(AndroidJUnit4.class)
public final class EditorFormattingTest {

    @Test
    public void unicodeCombinedStylesAndParagraphsRoundTripWithoutChangingPlainText() {
        String body = "Hello 🪶\nမြန်မာ world\n";
        int secondParagraph = body.indexOf('\n') + 1;
        SpannableStringBuilder original = new SpannableStringBuilder(body);
        original.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), 6, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        original.setSpan(new BulletSpan(), 0, secondParagraph, Spanned.SPAN_PARAGRAPH);
        original.setSpan(new QuoteSpan(), secondParagraph, body.length(), Spanned.SPAN_PARAGRAPH);
        original.setSpan(new UnderlineSpan(), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        SpannableStringBuilder restored = RichTextCodec.decode(body, RichTextCodec.encode(original));

        assertEquals(body, restored.toString());
        assertEquals(Typeface.NORMAL, styleAt(restored, 0));
        assertEquals(Typeface.BOLD_ITALIC, styleAt(restored, 6));
        assertEquals(Typeface.BOLD_ITALIC, styleAt(restored, 7));
        assertEquals(Typeface.NORMAL, styleAt(restored, secondParagraph));
        assertRanges(restored, BulletSpan.class, "0:" + secondParagraph);
        assertRanges(restored, QuoteSpan.class, secondParagraph + ":" + body.length());
        assertEquals(0, restored.getSpans(0, restored.length(), UnderlineSpan.class).length);
    }

    @Test
    public void malformedOrSurrogateSplittingMetadataCannotDamagePlainText() {
        String body = "A🪶B";
        String splitSurrogate = "{\"version\":1,\"spans\":[{\"type\":\"bold\",\"start\":2,\"end\":3}]}";

        SpannableStringBuilder restored = RichTextCodec.decode(body, splitSurrogate);
        assertEquals(body, restored.toString());
        assertEquals(0, restored.getSpans(0, restored.length(), StyleSpan.class).length);
        assertEquals(body, RichTextCodec.decode(body, "{invalid").toString());
        assertEquals("", RichTextCodec.encode("Plain text"));
    }

    @Test
    public void selectedInlineToggleSplitsCoverageAndPreservesOtherStyleAndOutsideText() {
        onMain(() -> {
            EditText editor = editor("abcdef");
            editor.getText().setSpan(new StyleSpan(Typeface.BOLD_ITALIC),
                    0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            editor.setSelection(2, 4);
            EditorFormatting formatting = new EditorFormatting(editor, () -> { });

            formatting.toggleBold();

            assertEquals("abcdef", editor.getText().toString());
            assertEquals(Typeface.BOLD_ITALIC, styleAt(editor.getText(), 0));
            assertEquals(Typeface.ITALIC, styleAt(editor.getText(), 2));
            assertEquals(Typeface.ITALIC, styleAt(editor.getText(), 3));
            assertEquals(Typeface.BOLD_ITALIC, styleAt(editor.getText(), 5));
            assertFalse(formatting.isBold());
            assertTrue(formatting.isItalic());

            formatting.toggleItalic();

            assertEquals(Typeface.NORMAL, styleAt(editor.getText(), 2));
            assertEquals(Typeface.BOLD_ITALIC, styleAt(editor.getText(), 0));
            assertEquals(Typeface.BOLD_ITALIC, styleAt(editor.getText(), 5));
            assertFalse(formatting.isItalic());
            assertEquals("abcdef", editor.getText().toString());
        });
    }

    @Test
    public void multilineBulletsAndQuotesToggleOnlySelectedParagraphsWithoutTextMarkers() {
        onMain(() -> {
            EditText editor = editor("One\nTwo\nThree");
            editor.setSelection(1, 5);
            EditorFormatting formatting = new EditorFormatting(editor, () -> { });

            formatting.toggleBullets();
            formatting.toggleQuote();

            assertEquals("One\nTwo\nThree", editor.getText().toString());
            assertRanges(editor.getText(), BulletSpan.class, "0:4", "4:8");
            assertRanges(editor.getText(), QuoteSpan.class, "0:4", "4:8");

            editor.setSelection(5);
            formatting.onSelectionChanged();
            formatting.toggleBullets();

            assertRanges(editor.getText(), BulletSpan.class, "0:4");
            assertRanges(editor.getText(), QuoteSpan.class, "0:4", "4:8");
            assertFalse(formatting.isBullets());
            assertTrue(formatting.isQuote());
            assertEquals("One\nTwo\nThree", editor.getText().toString());
        });
    }

    @Test
    public void collapsedInlineModeAppliesToFutureTypingAndCanBeTurnedOff() {
        onMain(() -> {
            EditText editor = editor("One");
            editor.setSelection(3);
            EditorFormatting formatting = new EditorFormatting(editor, () -> { });
            formatting.toggleBold();
            editor.getText().insert(3, " two");
            editor.setSelection(7);
            formatting.onTextInserted(3, 4);

            assertEquals(Typeface.NORMAL, styleAt(editor.getText(), 1));
            assertEquals(Typeface.BOLD, styleAt(editor.getText(), 4));
            assertEquals(Typeface.BOLD, styleAt(editor.getText(), 6));

            formatting.toggleBold();
            editor.getText().insert(7, " plain");
            editor.setSelection(13);
            formatting.onTextInserted(7, 6);

            assertEquals("One two plain", editor.getText().toString());
            assertEquals(Typeface.BOLD, styleAt(editor.getText(), 4));
            assertEquals(Typeface.NORMAL, styleAt(editor.getText(), 8));
            assertFalse(formatting.isBold());
        });
    }

    @Test
    public void emptyBodyPendingParagraphModesEncodeSafelyThenContinueAcrossNewline() {
        onMain(() -> {
            EditText editor = editor("");
            editor.setSelection(0);
            EditorFormatting formatting = new EditorFormatting(editor, () -> { });
            formatting.toggleBullets();
            formatting.toggleQuote();

            assertTrue(formatting.isBullets());
            assertTrue(formatting.isQuote());
            assertEquals("", RichTextCodec.encode(editor.getText()));

            editor.getText().insert(0, "One");
            editor.setSelection(3);
            formatting.onTextInserted(0, 3);
            editor.getText().insert(3, "\n");
            editor.setSelection(4);
            formatting.onTextInserted(3, 1);
            editor.getText().insert(4, "Two");
            editor.setSelection(7);
            formatting.onTextInserted(4, 3);

            assertEquals("One\nTwo", editor.getText().toString());
            assertRanges(editor.getText(), BulletSpan.class, "0:4", "4:7");
            assertRanges(editor.getText(), QuoteSpan.class, "0:4", "4:7");
            SpannableStringBuilder restored = RichTextCodec.decode(
                    editor.getText().toString(), RichTextCodec.encode(editor.getText()));
            assertRanges(restored, BulletSpan.class, "0:4", "4:7");
            assertRanges(restored, QuoteSpan.class, "0:4", "4:7");
        });
    }

    @Test
    public void deletingParagraphBoundaryNormalizesBulletsAndQuotesWithoutDuplication() {
        onMain(() -> {
            EditText editor = editor("One\nTwo");
            editor.setSelection(0, editor.length());
            EditorFormatting formatting = new EditorFormatting(editor, () -> { });
            formatting.toggleBullets();
            formatting.toggleQuote();
            editor.setSelection(3);
            formatting.onSelectionChanged();

            editor.getText().delete(3, 4);
            editor.setSelection(3);
            formatting.onTextInserted(3, 0);

            assertEquals("OneTwo", editor.getText().toString());
            assertRanges(editor.getText(), BulletSpan.class, "0:6");
            assertRanges(editor.getText(), QuoteSpan.class, "0:6");
        });
    }

    private static EditText editor(String text) {
        Context context = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_InkNote);
        EditText editor = new EditText(context);
        editor.setText(text);
        return editor;
    }

    private static int styleAt(Spanned text, int index) {
        int result = Typeface.NORMAL;
        for (StyleSpan span : text.getSpans(index, index + 1, StyleSpan.class)) {
            if (text.getSpanStart(span) <= index && text.getSpanEnd(span) > index) {
                result |= span.getStyle();
            }
        }
        return result;
    }

    private static void assertRanges(Spanned text, Class<?> type, String... expected) {
        List<String> actual = new ArrayList<>();
        for (Object span : text.getSpans(0, text.length(), type)) {
            actual.add(text.getSpanStart(span) + ":" + text.getSpanEnd(span));
        }
        Collections.sort(actual);
        List<String> wanted = new ArrayList<>(Arrays.asList(expected));
        Collections.sort(wanted);
        assertEquals(wanted, actual);
    }

    private static void onMain(Runnable work) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(work);
    }
}

package wlv.ac.uk.inknote.ui.library;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import wlv.ac.uk.inknote.model.BlogPost;

/** Pure presentation rules shared by the library and its tests. */
public final class LibraryContent {
    public enum Filter { ALL, DRAFTS, FAVOURITES }
    public enum Sort { NEWEST, OLDEST, TITLE }

    private LibraryContent() { }

    public static List<BlogPost> select(List<BlogPost> source, Filter filter, Sort sort) {
        List<BlogPost> selected = new ArrayList<>();
        for (BlogPost post : source) {
            if (filter == Filter.DRAFTS && !post.isDraft()) continue;
            if (filter == Filter.FAVOURITES && !post.isFavourite()) continue;
            selected.add(post);
        }
        Comparator<BlogPost> comparator;
        if (sort == Sort.TITLE) {
            comparator = Comparator.comparing(BlogPost::getTitle, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                    .thenComparingLong(BlogPost::getId);
        } else {
            comparator = Comparator.comparingLong(BlogPost::getUpdatedAt).thenComparingLong(BlogPost::getId);
            if (sort == Sort.NEWEST) comparator = comparator.reversed();
        }
        selected.sort(comparator);
        return selected;
    }

    public static int wordCount(String body) {
        if (body == null || body.isEmpty()) return 0;
        boolean inWord = false;
        int count = 0;
        for (int index = 0; index < body.length();) {
            int codePoint = body.codePointAt(index);
            boolean space = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
            if (!space && !inWord) count++;
            inWord = !space;
            index += Character.charCount(codePoint);
        }
        return count;
    }
}

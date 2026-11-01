package wlv.ac.uk.inknote.ui.library;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import wlv.ac.uk.inknote.model.BlogPost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LibraryContentTest {

    @Test
    public void allFilterIncludesSavedDraftAndFavouritePosts() {
        List<BlogPost> source = Arrays.asList(
                post(1, "Saved", 100, false, false),
                post(2, "Draft", 200, true, false),
                post(3, "Favourite", 300, false, true),
                post(4, "Favourite draft", 400, true, true)
        );

        assertEquals(Arrays.asList(4L, 3L, 2L, 1L), ids(
                LibraryContent.select(source, LibraryContent.Filter.ALL, LibraryContent.Sort.NEWEST)));
    }

    @Test
    public void draftsAndFavouritesRemainIndependentFilters() {
        List<BlogPost> source = Arrays.asList(
                post(1, "Saved", 100, false, false),
                post(2, "Draft", 200, true, false),
                post(3, "Favourite", 300, false, true),
                post(4, "Favourite draft", 400, true, true)
        );

        assertEquals(Arrays.asList(4L, 2L), ids(
                LibraryContent.select(source, LibraryContent.Filter.DRAFTS, LibraryContent.Sort.NEWEST)));
        assertEquals(Arrays.asList(4L, 3L), ids(
                LibraryContent.select(source, LibraryContent.Filter.FAVOURITES, LibraryContent.Sort.NEWEST)));
    }

    @Test
    public void newestUsesUpdatedTimeAndDescendingIdForTies() {
        List<BlogPost> source = Arrays.asList(
                post(8, "Older", 100, false, false),
                post(2, "Same time", 500, false, false),
                post(9, "Same time", 500, false, false)
        );

        assertEquals(Arrays.asList(9L, 2L, 8L), ids(
                LibraryContent.select(source, LibraryContent.Filter.ALL, LibraryContent.Sort.NEWEST)));
    }

    @Test
    public void oldestUsesUpdatedTimeAndAscendingIdForTies() {
        List<BlogPost> source = Arrays.asList(
                post(9, "Same time", 500, false, false),
                post(2, "Same time", 500, false, false),
                post(8, "Older", 100, false, false)
        );

        assertEquals(Arrays.asList(8L, 2L, 9L), ids(
                LibraryContent.select(source, LibraryContent.Filter.ALL, LibraryContent.Sort.OLDEST)));
    }

    @Test
    public void titleSortIgnoresCaseAndBreaksTiesById() {
        List<BlogPost> source = Arrays.asList(
                post(8, "zebra", 100, false, false),
                post(5, "alpha", 200, false, false),
                post(2, "Alpha", 300, false, false),
                post(4, "Beta", 400, false, false)
        );

        assertEquals(Arrays.asList(2L, 5L, 4L, 8L), ids(
                LibraryContent.select(source, LibraryContent.Filter.ALL, LibraryContent.Sort.TITLE)));
    }

    @Test
    public void titleSortAcceptsNullAndEmptyTitlesWithDeterministicNullOrdering() {
        List<BlogPost> source = Arrays.asList(
                post(7, null, 100, false, false),
                post(9, "Beta", 200, false, false),
                post(3, "", 300, false, false),
                post(2, null, 400, false, false),
                post(5, "Alpha", 500, false, false)
        );

        assertEquals(Arrays.asList(2L, 7L, 3L, 5L, 9L), ids(
                LibraryContent.select(source, LibraryContent.Filter.ALL, LibraryContent.Sort.TITLE)));
    }

    @Test
    public void selectionAcceptsImmutableSourceAndLeavesItsOrderUnchanged() {
        List<BlogPost> source = Collections.unmodifiableList(Arrays.asList(
                post(1, "First", 100, false, false),
                post(2, "Second", 200, true, false)
        ));

        List<BlogPost> result = LibraryContent.select(
                source, LibraryContent.Filter.ALL, LibraryContent.Sort.NEWEST);

        assertEquals(Arrays.asList(2L, 1L), ids(result));
        assertEquals(Arrays.asList(1L, 2L), ids(source));
        assertEquals("First", source.get(0).getTitle());
        assertTrue(source.get(1).isDraft());
    }

    @Test
    public void emptyLibraryAndUnmatchedFilterProduceEmptyLists() {
        assertTrue(LibraryContent.select(Collections.emptyList(),
                LibraryContent.Filter.ALL, LibraryContent.Sort.NEWEST).isEmpty());
        assertTrue(LibraryContent.select(
                Collections.singletonList(post(1, "Saved", 100, false, false)),
                LibraryContent.Filter.FAVOURITES, LibraryContent.Sort.NEWEST).isEmpty());
    }

    @Test
    public void wordCountHandlesLeadingTrailingAndRepeatedWhitespace() {
        assertEquals(0, LibraryContent.wordCount(""));
        assertEquals(0, LibraryContent.wordCount(" \t \r\n"));
        assertEquals(0, LibraryContent.wordCount("\u2003\u2009"));
        assertEquals(1, LibraryContent.wordCount("offline-first"));
        assertEquals(4, LibraryContent.wordCount("  One\t two\nthree\r\nfour  "));
    }

    private static BlogPost post(long id, String title, long updatedAt,
                                 boolean draft, boolean favourite) {
        BlogPost post = new BlogPost();
        post.setId(id);
        post.setTitle(title);
        post.setBody("Message body");
        post.setCreatedAt(1);
        post.setUpdatedAt(updatedAt);
        post.setDraft(draft);
        post.setFavourite(favourite);
        return post;
    }

    private static List<Long> ids(List<BlogPost> posts) {
        List<Long> result = new ArrayList<>();
        for (BlogPost post : posts) {
            result.add(post.getId());
        }
        return result;
    }
}

package wlv.ac.uk.inknote.ui.library;

import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.tabs.TabLayout;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import wlv.ac.uk.inknote.InkNoteApplication;
import wlv.ac.uk.inknote.R;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.ui.MainActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** UI regressions without seeding, updating, or deleting the user's messages. */
@RunWith(AndroidJUnit4.class)
public final class LibraryScreenTest {
    private static final String LIBRARY_TAG = "library-screen-test";

    @Test
    public void searchFilterSortAndDismissedBannerSurviveActivityRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.getSupportFragmentManager().executePendingTransactions();
                activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, new LibraryFragment(), LIBRARY_TAG)
                        .commitNow();

                Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(LIBRARY_TAG);
                assertNotNull(fragment);
                View root = fragment.requireView();
                EditText query = root.findViewById(R.id.search);
                query.setText("recreation regression query");
                TabLayout filters = root.findViewById(R.id.filter_tabs);
                TabLayout.Tab drafts = filters.getTabAt(1);
                assertNotNull(drafts);
                drafts.select();
                assertTrue(root.findViewById(R.id.dismiss_banner).performClick());

                LibraryViewModel model = new ViewModelProvider(fragment).get(LibraryViewModel.class);
                model.setSort(LibraryContent.Sort.TITLE);
            });

            scenario.recreate();

            scenario.onActivity(activity -> {
                Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(LIBRARY_TAG);
                assertNotNull(fragment);
                View root = fragment.requireView();
                EditText query = root.findViewById(R.id.search);
                TabLayout filters = root.findViewById(R.id.filter_tabs);

                assertEquals("recreation regression query", query.getText().toString());
                assertEquals(1, filters.getSelectedTabPosition());
                assertEquals(View.GONE, root.findViewById(R.id.offline_banner).getVisibility());
                assertEquals(activity.getString(R.string.library_sort_title) + ", "
                                + activity.getString(R.string.library_sort_title_order),
                        root.findViewById(R.id.sort).getContentDescription().toString());
            });
        }
    }

    @Test
    public void nullTitleCardsShowFallbackWordCountAndCorrectFavouriteActions() throws InterruptedException {
        AtomicReference<LibraryAdapter> adapterRef = new AtomicReference<>();
        AtomicReference<LocalThumbnailLoader> imagesRef = new AtomicReference<>();
        AtomicReference<RecyclerView> parentRef = new AtomicReference<>();
        AtomicReference<RecyclerView.ViewHolder> holderRef = new AtomicReference<>();
        CountDownLatch initialRows = new CountDownLatch(1);
        CountDownLatch changedRows = new CountDownLatch(1);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    LocalThumbnailLoader images = new LocalThumbnailLoader(activity,
                            ((InkNoteApplication) activity.getApplication()).getContainer().getExecutors());
                    imagesRef.set(images);
                    RecyclerView parent = new RecyclerView(new ContextThemeWrapper(activity, R.style.Theme_InkNote));
                    parent.setLayoutManager(new LinearLayoutManager(activity));
                    parentRef.set(parent);
                    LibraryAdapter adapter = new LibraryAdapter(new NoOpActions(), images);
                    adapterRef.set(adapter);
                    parent.setAdapter(adapter);
                    adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                        @Override public void onChanged() { initialRows.countDown(); }
                        @Override public void onItemRangeInserted(int start, int count) { initialRows.countDown(); }
                    });
                    adapter.show(Collections.singletonList(untitledDraft(true)), LibraryContent.Filter.DRAFTS);
                });

                // Await adapter notifications on the instrumentation thread, never the main thread.
                assertTrue("Initial adapter rows were not committed.", initialRows.await(5, TimeUnit.SECONDS));

                scenario.onActivity(activity -> {
                    LibraryAdapter adapter = adapterRef.get();
                    assertEquals(1, adapter.getItemCount());
                    RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(
                            parentRef.get(), adapter.getItemViewType(0));
                    holderRef.set(holder);
                    adapter.onBindViewHolder(holder, 0);

                    String title = activity.getString(R.string.library_untitled);
                    TextView displayedTitle = holder.itemView.findViewById(R.id.post_title);
                    TextView wordCount = holder.itemView.findViewById(R.id.word_count);
                    ImageButton favourite = holder.itemView.findViewById(R.id.favourite);
                    assertEquals(title, displayedTitle.getText().toString());
                    assertEquals(activity.getResources().getQuantityString(R.plurals.library_words, 4, 4),
                            wordCount.getText().toString());
                    assertEquals(activity.getString(R.string.library_remove_favourite, title),
                            favourite.getContentDescription().toString());
                    assertTrue(favourite.isSelected());
                    assertEquals(View.GONE, holder.itemView.findViewById(R.id.hero).getVisibility());

                    adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                        @Override public void onChanged() { changedRows.countDown(); }
                        @Override public void onItemRangeChanged(int start, int count) { changedRows.countDown(); }
                    });
                    // A new snapshot with the same ID exercises the actual asynchronous diff.
                    adapter.show(Collections.singletonList(untitledDraft(false)), LibraryContent.Filter.DRAFTS);
                });

                assertTrue("Changed favourite state was not committed.", changedRows.await(5, TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    RecyclerView.ViewHolder holder = holderRef.get();
                    adapterRef.get().onBindViewHolder(holder, 0);
                    ImageButton favourite = holder.itemView.findViewById(R.id.favourite);
                    assertFalse(favourite.isSelected());
                    assertEquals(activity.getString(R.string.library_add_favourite,
                                    activity.getString(R.string.library_untitled)),
                            favourite.getContentDescription().toString());
                });
            } finally {
                scenario.onActivity(activity -> {
                    if (holderRef.get() != null && adapterRef.get() != null) {
                        adapterRef.get().onViewRecycled(holderRef.get());
                    }
                    if (parentRef.get() != null) parentRef.get().setAdapter(null);
                    if (imagesRef.get() != null) imagesRef.get().close();
                });
            }
        }
    }

    private static BlogPost untitledDraft(boolean favourite) {
        BlogPost post = new BlogPost();
        post.setId(1L);
        post.setTitle(null);
        post.setBody("One two three four");
        post.setCreatedAt(100L);
        post.setUpdatedAt(200L);
        post.setDraft(true);
        post.setFavourite(favourite);
        return post;
    }

    private static final class NoOpActions implements LibraryAdapter.Actions {
        @Override public void open(BlogPost post) { }
        @Override public void favourite(BlogPost post) { }
        @Override public void more(View anchor, BlogPost post) { }
        @Override public void seeAllFavourites() { }
        @Override public void sort() { }
    }
}

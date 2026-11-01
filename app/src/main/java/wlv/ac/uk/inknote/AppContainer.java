package wlv.ac.uk.inknote;

import android.content.Context;
import wlv.ac.uk.inknote.data.local.InkNoteDbHelper;
import wlv.ac.uk.inknote.data.local.OnboardingPreferences;
import wlv.ac.uk.inknote.data.repository.BlogPostRepository;
import wlv.ac.uk.inknote.services.BlogPostService;
import wlv.ac.uk.inknote.services.OnboardingService;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.AttachmentFiles;

public final class AppContainer {

    private final AppExecutors executors;
    private final InkNoteDbHelper databaseHelper;
    private final BlogPostService blogPostService;
    private final OnboardingService onboardingService;

    public AppContainer(Context context) {
        Context appContext = context.getApplicationContext();

        executors = new AppExecutors();
        onboardingService = new OnboardingService(new OnboardingPreferences(appContext));

        /*
         * Constructing the helper does not open the database.
         * Opening happens later on the I/O executor.
         */
        databaseHelper = new InkNoteDbHelper(appContext);

        BlogPostRepository repository = new BlogPostRepository(databaseHelper);

        AttachmentFiles attachmentFiles = new AttachmentFiles(appContext);

        blogPostService = new BlogPostService(repository, attachmentFiles);

        /*
         * Recover unused files left by a previous interrupted
         * cleanup. Service methods log their own outcomes.
         */
        executors.io().execute(() -> blogPostService.cleanupUnusedImages());
    }

    public AppExecutors getExecutors() {
        return executors;
    }

    public BlogPostService getBlogPostService() {
        return blogPostService;
    }

    public OnboardingService getOnboardingService() {
        return onboardingService;
    }
}

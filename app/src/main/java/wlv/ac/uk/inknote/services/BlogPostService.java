package wlv.ac.uk.inknote.services;


import android.database.sqlite.SQLiteException;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import wlv.ac.uk.inknote.data.repository.BlogPostRepository;
import wlv.ac.uk.inknote.model.BlogPost;
import wlv.ac.uk.inknote.model.DeleteSummary;
import wlv.ac.uk.inknote.utils.AppExecutors;
import wlv.ac.uk.inknote.utils.AppLogger;
import wlv.ac.uk.inknote.utils.AttachmentFiles;
import wlv.ac.uk.inknote.utils.InputValidator;
import wlv.ac.uk.inknote.utils.ServiceResult;

public final class BlogPostService {

    private final BlogPostRepository repository;
    private final AttachmentFiles attachmentFiles;
    private final Object operationLock = new Object();
    public BlogPostService(
            BlogPostRepository repository,
            AttachmentFiles attachmentFiles
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository"
        );

        this.attachmentFiles = Objects.requireNonNull(
                attachmentFiles,
                "attachmentFiles"
        );
    }

    private static long nowUtcMillis() {
        return Math.max(
                0L,
                System.currentTimeMillis()
        );
    }

    public ServiceResult<BlogPost> createPost(
            String title,
            String body
    ) {
        return execute(
                AppLogger.Operation.CREATE_POST,
                () -> {
                    String validTitle =
                            InputValidator.optionalTitle(title);

                    String validBody =
                            InputValidator.requiredBody(body);

                    return repository.insert(
                            validTitle,
                            validBody,
                            nowUtcMillis()
                    );
                }
        );
    }

    public ServiceResult<BlogPost> getPostById(long id) {
        return execute(
                AppLogger.Operation.GET_POST,
                () -> {
                    InputValidator.requirePositiveId(id);

                    BlogPost post = repository.findById(id);

                    if (post == null) {
                        throw new PostNotFoundException();
                    }

                    return post;
                }
        );
    }

    public ServiceResult<List<BlogPost>> getPosts() {
        return getPosts(
                InputValidator.DEFAULT_PAGE_SIZE,
                0
        );
    }

    public ServiceResult<List<BlogPost>> getPosts(
            int limit,
            int offset
    ) {
        return execute(
                AppLogger.Operation.LIST_POSTS,
                () -> {
                    InputValidator.requireValidPage(
                            limit,
                            offset
                    );

                    return repository.findPage(
                            limit,
                            offset
                    );
                }
        );
    }

    /**
     * All matching local posts, with flags, for library filtering and sorting.
     * Call on the application's shared I/O executor.
     */
    public ServiceResult<List<BlogPost>> getLibraryPosts(String query) {
        return execute(
                AppLogger.Operation.LIST_LIBRARY,
                () -> repository.findLibrary(InputValidator.searchQuery(query))
        );
    }

    public ServiceResult<BlogPost> setFavourite(long id, boolean favourite) {
        return execute(
                AppLogger.Operation.SET_FAVOURITE,
                () -> {
                    InputValidator.requirePositiveId(id);
                    BlogPost post = repository.setFavourite(id, favourite);
                    if (post == null) {
                        throw new PostNotFoundException();
                    }
                    return post;
                }
        );
    }

    public ServiceResult<BlogPost> setDraft(long id, boolean draft) {
        return execute(
                AppLogger.Operation.SET_DRAFT,
                () -> {
                    InputValidator.requirePositiveId(id);
                    BlogPost post = repository.setDraft(id, draft, nowUtcMillis());
                    if (post == null) {
                        throw new PostNotFoundException();
                    }
                    return post;
                }
        );
    }

    public ServiceResult<BlogPost> updatePost(
            long id,
            String title,
            String body
    ) {
        return execute(
                AppLogger.Operation.UPDATE_POST,
                () -> {
                    InputValidator.requirePositiveId(id);

                    String validTitle =
                            InputValidator.optionalTitle(title);

                    String validBody =
                            InputValidator.requiredBody(body);

                    BlogPost updated = repository.update(
                            id,
                            validTitle,
                            validBody,
                            nowUtcMillis()
                    );

                    if (updated == null) {
                        throw new PostNotFoundException();
                    }

                    return updated;
                }
        );
    }

    public ServiceResult<List<BlogPost>> searchPosts(
            String query
    ) {
        return searchPosts(
                query,
                InputValidator.DEFAULT_PAGE_SIZE,
                0
        );
    }

    public ServiceResult<List<BlogPost>> searchPosts(
            String query,
            int limit,
            int offset
    ) {
        return execute(
                AppLogger.Operation.SEARCH_POSTS,
                () -> {
                    InputValidator.requireValidPage(
                            limit,
                            offset
                    );

                    String validQuery =
                            InputValidator.searchQuery(query);

                    return repository.search(
                            validQuery,
                            limit,
                            offset
                    );
                }
        );
    }

    public ServiceResult<DeleteSummary> deletePost(long id) {
        return execute(
                AppLogger.Operation.DELETE_POST,
                () -> {
                    InputValidator.requirePositiveId(id);

                    int deletedCount = repository.deletePosts(
                            Collections.singletonList(id)
                    );

                    if (deletedCount == 0) {
                        throw new PostNotFoundException();
                    }

                    return new DeleteSummary(
                            deletedCount,
                            cleanupAfterCommittedDelete()
                    );
                }
        );
    }

    public ServiceResult<DeleteSummary> deletePosts(
            List<Long> ids
    ) {
        return execute(
                AppLogger.Operation.DELETE_POSTS,
                () -> {
                    List<Long> validIds =
                            InputValidator.uniqueIds(ids);

                    if (validIds.isEmpty()) {
                        return new DeleteSummary(
                                0,
                                false
                        );
                    }

                    int deletedCount =
                            repository.deletePosts(validIds);

                    boolean cleanupPending =
                            deletedCount > 0
                                    && cleanupAfterCommittedDelete();

                    return new DeleteSummary(
                            deletedCount,
                            cleanupPending
                    );
                }
        );
    }

    public ServiceResult<AttachmentFiles.Cleanup>
    cleanupUnusedImages() {
        return execute(
                AppLogger.Operation.CLEANUP_IMAGES,
                () -> {
                    AttachmentFiles.Cleanup cleanup =
                            attachmentFiles.removeUnreferenced(
                                    repository
                                            .getReferencedAttachmentPaths()
                            );

                    if (cleanup.isPending()) {
                        AppLogger.cleanupPending();
                    }

                    return cleanup;
                }
        );
    }

    /**
     * SQLite deletion has already committed.
     * <p>
     * File cleanup failures must not incorrectly report that
     * the database deletion failed.
     */
    private boolean cleanupAfterCommittedDelete() {
        try {
            AttachmentFiles.Cleanup cleanup =
                    attachmentFiles.removeUnreferenced(
                            repository.getReferencedAttachmentPaths()
                    );

            if (cleanup.isPending()) {
                AppLogger.cleanupPending();
            }

            return cleanup.isPending();

        } catch (IOException | RuntimeException exception) {
            AppLogger.failure(
                    AppLogger.Operation.CLEANUP_IMAGES,
                    exception
            );

            return true;
        }
    }

    private <T> ServiceResult<T> execute(
            AppLogger.Operation operation,
            Work<T> work
    ) {
        AppExecutors.requireWorkerThread();

        synchronized (operationLock) {
            long startedAt = SystemClock.elapsedRealtime();

            try {
                T data = work.execute();

                AppLogger.success(
                        operation,
                        SystemClock.elapsedRealtime() - startedAt
                );

                return ServiceResult.success(data);

            } catch (
                    InputValidator.InvalidInputException exception
            ) {
                AppLogger.rejected(
                        operation,
                        ServiceResult.ErrorCode.VALIDATION
                );

                return ServiceResult.failure(
                        ServiceResult.ErrorCode.VALIDATION,
                        exception.getMessage(),
                        exception
                );

            } catch (PostNotFoundException exception) {
                AppLogger.rejected(
                        operation,
                        ServiceResult.ErrorCode.NOT_FOUND
                );

                return ServiceResult.failure(
                        ServiceResult.ErrorCode.NOT_FOUND,
                        "This message could not be found.",
                        exception
                );

            } catch (SQLiteException exception) {
                AppLogger.failure(
                        operation,
                        exception
                );

                return ServiceResult.failure(
                        ServiceResult.ErrorCode.DATABASE,
                        "The message database could not complete "
                                + "this operation. Please try again.",
                        exception
                );

            } catch (
                    IOException | SecurityException exception
            ) {
                AppLogger.failure(
                        operation,
                        exception
                );

                return ServiceResult.failure(
                        ServiceResult.ErrorCode.FILE_SYSTEM,
                        "Image storage is currently unavailable.",
                        exception
                );

            } catch (RuntimeException exception) {
                AppLogger.failure(
                        operation,
                        exception
                );

                return ServiceResult.failure(
                        ServiceResult.ErrorCode.INTERNAL,
                        "This operation could not be completed.",
                        exception
                );
            }
        }
    }

    private interface Work<T> {
        T execute() throws IOException;
    }

    private static final class PostNotFoundException
            extends RuntimeException {
    }
}
package wlv.ac.uk.inknote.model;

public class DeleteSummary {

    private int deletedCount;
    private boolean imageCleanupPending;

    public DeleteSummary() {
    }

    public DeleteSummary(int deletedCount, boolean imageCleanupPending) {
        this.deletedCount = deletedCount;
        this.imageCleanupPending = imageCleanupPending;
    }

    public int getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(int deletedCount) {
        this.deletedCount = deletedCount;
    }

    public boolean isImageCleanupPending() {
        return imageCleanupPending;
    }

    public void setImageCleanupPending(boolean imageCleanupPending) {
        this.imageCleanupPending = imageCleanupPending;
    }
}
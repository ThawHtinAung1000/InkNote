package wlv.ac.uk.inknote.model;

public class PostAttachment {

    private long id;
    private long postId;
    private String relativePath;
    private String mimeType;

    public PostAttachment() {
    }

    public PostAttachment(long id, long postId, String relativePath, String mimeType) {
        this.id = id;
        this.postId = postId;
        this.relativePath = relativePath;
        this.mimeType = mimeType;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getPostId() {
        return postId;
    }

    public void setPostId(long postId) {
        this.postId = postId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
}
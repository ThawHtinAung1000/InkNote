package wlv.ac.uk.inknote.model;

public class BlogPost {

    private long id;
    private String title;
    private String body;

    // UTC Unix epoch milliseconds.
    private long createdAt;
    private long updatedAt;

    private PostAttachment attachment;
    private boolean draft;
    private boolean favourite;

    public BlogPost() {
    }

    public BlogPost(long id, String title, String body, long createdAt, long updatedAt, PostAttachment attachment) {
        this(id, title, body, createdAt, updatedAt, attachment, false, false);
    }

    public BlogPost(long id, String title, String body, long createdAt, long updatedAt,
                    PostAttachment attachment, boolean draft, boolean favourite) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.attachment = attachment;
        this.draft = draft;
        this.favourite = favourite;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public PostAttachment getAttachment() {
        return attachment;
    }

    public void setAttachment(PostAttachment attachment) {
        this.attachment = attachment;
    }

    public boolean hasAttachment() {
        return attachment != null;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public boolean isFavourite() {
        return favourite;
    }

    public void setFavourite(boolean favourite) {
        this.favourite = favourite;
    }
}
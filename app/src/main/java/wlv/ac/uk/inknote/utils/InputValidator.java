package wlv.ac.uk.inknote.utils;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class InputValidator {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 100_000;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_LENGTH = 32;
    public static final int MAX_FORMAT_SPANS = 2_000;
    public static final int MAX_BODY_FORMAT_LENGTH = 262_144;

    private InputValidator() {
    }

    public static void requirePositiveId(long id) {
        if (id <= 0) {
            throw new InvalidInputException("Please select a valid message.");
        }
    }

    public static String optionalTitle(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }

        rejectNullCharacter(title);
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new InvalidInputException("Keep the title within 200 characters.");
        }

        return title;
    }

    public static String requiredBody(String body) {
        if (body == null || isBlank(body)) {
            throw new InvalidInputException("Please enter a message.");
        }

        rejectNullCharacter(body);
        if (body.length() > MAX_BODY_LENGTH) {
            throw new InvalidInputException("Keep the message within 100,000 characters.");
        }

        // Preserve line breaks, spacing, and non-English text.
        return body;
    }

    public static String searchQuery(String query) {
        if (query == null) {
            return "";
        }

        rejectNullCharacter(query);

        // Preserve spaces because they can be part of a search.
        return query;
    }

    public static void requireValidPage(int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new InvalidInputException("Page size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }

        if (offset < 0) {
            throw new InvalidInputException("Page offset cannot be negative.");
        }
    }

    public static List<Long> uniqueIds(List<Long> ids) {
        if (ids == null) {
            throw new InvalidInputException("Please provide the selected messages.");
        }

        Set<Long> unique = new LinkedHashSet<>();

        for (Long id : ids) {
            if (id == null) {
                throw new InvalidInputException("A selected message is invalid.");
            }

            requirePositiveId(id);
            unique.add(id);
        }

        return new ArrayList<>(unique);
    }

    public static String editorBody(String body, boolean draft) {
        if (!draft) {
            return requiredBody(body);
        }
        String value = body == null ? "" : body;
        rejectNullCharacter(value);
        if (value.length() > MAX_BODY_LENGTH) {
            throw new InvalidInputException("Keep the message within 100,000 characters.");
        }
        return value;
    }

    public static String editorKey(String key) {
        if (key == null) {
            throw new InvalidInputException("The editor session is unavailable. Reopen the editor.");
        }
        try {
            String canonical = UUID.fromString(key).toString();
            if (!canonical.equalsIgnoreCase(key)) {
                throw new IllegalArgumentException();
            }
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputException("The editor session is unavailable. Reopen the editor.");
        }
    }

    public static List<String> editorTags(List<String> tags) {
        List<String> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (tags == null) {
            return result;
        }
        for (String tag : tags) {
            if (tag == null) {
                throw new InvalidInputException("Enter a valid tag.");
            }
            for (int offset = 0; offset < tag.length();) {
                int codePoint = tag.codePointAt(offset);
                if (Character.isISOControl(codePoint)) {
                    throw new InvalidInputException("Tags cannot contain control characters.");
                }
                offset += Character.charCount(codePoint);
            }
            int start = 0;
            int end = tag.length();
            while (start < end && isTagSpace(tag.codePointAt(start))) {
                start += Character.charCount(tag.codePointAt(start));
            }
            while (end > start && isTagSpace(tag.codePointBefore(end))) {
                end -= Character.charCount(tag.codePointBefore(end));
            }
            String trimmed = tag.substring(start, end);
            if (trimmed.isEmpty()
                    || trimmed.codePointCount(0, trimmed.length()) > MAX_TAG_LENGTH) {
                throw new InvalidInputException("Use between 1 and 32 characters for each tag.");
            }
            if (keys.add(trimmed.toLowerCase(Locale.ROOT))) {
                result.add(trimmed);
            }
            if (result.size() > MAX_TAGS) {
                throw new InvalidInputException("Add up to 10 tags.");
            }
        }
        return result;
    }

    private static boolean isTagSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * Validates UTF-16 span offsets against the plain body and stores only supported fields.
     * An absent version is interpreted as v1 for compatibility with the initial editor format.
     */
    public static String editorBodyFormat(String bodyFormat, String body) {
        if (bodyFormat == null || bodyFormat.isEmpty()) {
            return "";
        }
        if (bodyFormat.length() > MAX_BODY_FORMAT_LENGTH) {
            throw new InvalidInputException("This message has too much formatting.");
        }
        try {
            JSONObject source = new JSONObject(bodyFormat);
            if (source.has("version") && jsonInteger(source, "version") != 1) {
                throw new JSONException("Unsupported format version.");
            }
            JSONArray spans = source.getJSONArray("spans");
            if (spans.length() > MAX_FORMAT_SPANS) {
                throw new InvalidInputException("This message has too much formatting.");
            }
            JSONArray normalized = new JSONArray();
            Set<String> seen = new LinkedHashSet<>();
            for (int index = 0; index < spans.length(); index++) {
                JSONObject span = spans.getJSONObject(index);
                String type = span.getString("type");
                if (!"bold".equals(type) && !"italic".equals(type)
                        && !"bullet".equals(type) && !"quote".equals(type)) {
                    throw new JSONException("Unsupported span type.");
                }
                int start = jsonInteger(span, "start");
                int end = jsonInteger(span, "end");
                if (start < 0 || end <= start || end > body.length()) {
                    throw new JSONException("Invalid span range.");
                }
                if (seen.add(type + ":" + start + ":" + end)) {
                    normalized.put(new JSONObject()
                            .put("type", type).put("start", start).put("end", end));
                }
            }
            if (normalized.length() == 0) {
                return "";
            }
            return new JSONObject().put("version", 1).put("spans", normalized).toString();
        } catch (JSONException invalid) {
            throw new InvalidInputException("Message formatting is invalid.");
        }
    }

    private static int jsonInteger(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Number)) {
            throw new JSONException("An integer is required.");
        }
        Number number = (Number) value;
        int integer = number.intValue();
        if (number.doubleValue() != integer) {
            throw new JSONException("An integer is required.");
        }
        return integer;
    }

    private static boolean isBlank(String text) {
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);

            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }

            index += Character.charCount(codePoint);
        }

        return true;
    }

    private static void rejectNullCharacter(String text) {
        if (text.indexOf('\0') >= 0) {
            throw new InvalidInputException("Text cannot contain a null character.");
        }
    }

    public static final class InvalidInputException extends RuntimeException {

        public InvalidInputException(String message) {
            super(message);
        }
    }
}

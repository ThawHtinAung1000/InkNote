package wlv.ac.uk.inknote.utils;


import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class InputValidator {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

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

        return title;
    }

    public static String requiredBody(String body) {
        if (body == null || isBlank(body)) {
            throw new InvalidInputException("Please enter a message.");
        }

        rejectNullCharacter(body);

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

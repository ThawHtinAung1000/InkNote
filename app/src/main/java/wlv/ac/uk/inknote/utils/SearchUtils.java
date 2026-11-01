package wlv.ac.uk.inknote.utils;

import java.util.Objects;

public final class SearchUtils {

    private SearchUtils() {
    }

    /**
     * Escapes a literal substring for SQLite:
     * <p>
     * LIKE ? ESCAPE '\'
     */
    public static String literalContainsPattern(String query) {
        Objects.requireNonNull(query, "query");

        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

        return "%" + escaped + "%";
    }
}
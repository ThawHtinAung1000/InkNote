package wlv.ac.uk.inknote.utils;

import java.util.Objects;

public final class ServiceResult<T> {

    private final T data;
    private final Failure failure;

    private ServiceResult(T data, Failure failure) {
        this.data = data;
        this.failure = failure;
    }

    public static <T> ServiceResult<T> success(T data) {
        return new ServiceResult<>(Objects.requireNonNull(data, "data"), null);
    }

    public static <T> ServiceResult<T> failure(ErrorCode code, String message, Throwable cause) {
        return new ServiceResult<>(null, new Failure(code, message, cause));
    }

    public boolean isSuccess() {
        return failure == null;
    }

    public T getData() {
        if (!isSuccess()) {
            throw new IllegalStateException("This result contains a failure.");
        }

        return data;
    }

    public Failure getFailure() {
        if (isSuccess()) {
            throw new IllegalStateException("This result contains successful data.");
        }

        return failure;
    }

    public enum ErrorCode {
        VALIDATION, NOT_FOUND, DATABASE, FILE_SYSTEM, INTERNAL
    }

    public static final class Failure {

        private final ErrorCode code;
        private final String message;
        private final Throwable cause;

        private Failure(ErrorCode code, String message, Throwable cause) {
            this.code = Objects.requireNonNull(code, "code");

            this.message = Objects.requireNonNull(message, "message");

            this.cause = cause;
        }

        public ErrorCode getCode() {
            return code;
        }

        /**
         * Safe message for the user interface.
         */
        public String getMessage() {
            return message;
        }

        /**
         * Internal diagnostic cause.
         * <p>
         * Do not display or log its raw message.
         */
        public Throwable getCause() {
            return cause;
        }
    }
}
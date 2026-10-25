package dev.samridhi.orders;

/** A business error with a stable code that the HTTP layer maps to a status (see {@link HttpApi}). */
public final class OrderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        VALIDATION,            // 400: malformed or impossible request
        NOT_FOUND,             // 404
        INVALID_STATE,         // 409: e.g. shipping an unpaid order
        OUT_OF_STOCK,          // 409
        IDEMPOTENCY_MISMATCH   // 422: same Idempotency-Key reused for a different request
    }

    private final Code code;

    public OrderException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}

package com.pm.filmfuse.common.exception;

import java.util.Map;

/**
 * A 400 with {@code fieldErrors}, for validation that depends on more than an annotation can see
 * (e.g. an admin setting whose type and bounds come from its key). Messages are written for the caller.
 */
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public FieldValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}

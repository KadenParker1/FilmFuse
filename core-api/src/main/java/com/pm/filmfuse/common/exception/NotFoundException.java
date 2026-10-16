package com.pm.filmfuse.common.exception;

/** A requested resource does not exist or is not visible to the caller. Maps to 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

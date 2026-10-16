package com.pm.filmfuse.common.exception;

/**
 * The request is well-formed but conflicts with current state (e.g. a username already taken).
 * Maps to 409. The message is written for the caller and returned verbatim.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

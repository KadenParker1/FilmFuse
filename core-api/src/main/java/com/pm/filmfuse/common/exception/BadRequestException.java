package com.pm.filmfuse.common.exception;

/** The request is invalid in a way bean validation cannot express. Maps to 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}

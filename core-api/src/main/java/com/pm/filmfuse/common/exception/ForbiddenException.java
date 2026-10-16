package com.pm.filmfuse.common.exception;

/** The caller is authenticated but not allowed to do this (e.g. a suspended account). Maps to 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

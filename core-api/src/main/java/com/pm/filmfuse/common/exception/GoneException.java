package com.pm.filmfuse.common.exception;

/**
 * The thing existed but no longer can be used (e.g. an expired invite link). Maps to 410. The
 * message is written for the caller and returned verbatim.
 */
public class GoneException extends RuntimeException {

    public GoneException(String message) {
        super(message);
    }
}

package com.pm.filmfuse.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The one error shape every endpoint returns: {@code {message, fieldErrors?}}.
 * {@code fieldErrors} (field name to message) is present only for validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponseDTO(String message, Map<String, String> fieldErrors) {

    public static ErrorResponseDTO of(String message) {
        return new ErrorResponseDTO(message, null);
    }
}

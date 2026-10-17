package com.pm.filmfuse.rating.dto;

import com.pm.filmfuse.common.validation.SafeText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /ratings/{ratingId}/comments}. {@code body} is trimmed first, then must be 1-500
 * characters. Mirrored by hand in client/types/api.ts.
 */
public record CreateCommentDTO(
        @NotBlank(message = "A comment cannot be empty")
        @Size(max = 500, message = "A comment cannot exceed 500 characters")
        @SafeText(multiline = true)
        String body) {

    public CreateCommentDTO {
        body = body == null ? null : body.replace("\r\n", "\n").strip();
    }
}

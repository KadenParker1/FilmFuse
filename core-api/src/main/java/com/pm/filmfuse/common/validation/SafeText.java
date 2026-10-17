package com.pm.filmfuse.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Free text that other people will see (names, comments, notes, report details) must not carry
 * characters that change how it, or the text around it, renders (SECURITY.md #19):
 * <ul>
 *   <li>C0 / C1 control characters and DEL ({@code U+0000-U+001F}, {@code U+007F-U+009F}), except a
 *       line feed when {@link #multiline()} is true;</li>
 *   <li>Unicode bidi embeddings, overrides and isolates ({@code U+202A-U+202E},
 *       {@code U+2066-U+2069});</li>
 *   <li>a zero-width character ({@code U+200B-U+200D}, {@code U+2060}, {@code U+FEFF}) at the start
 *       or end. Inside the text they stay allowed, since emoji sequences need {@code U+200D}.</li>
 * </ul>
 * {@code null} is valid (combine with {@code @NotBlank} where required). Rejections go through the
 * normal validation handler: 400 with {@code fieldErrors}. Rules mirrored in
 * {@code client/lib/safeText.ts}.
 */
@Documented
@Constraint(validatedBy = SafeTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE,
        ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {

    String MESSAGE = "Cannot contain control characters or invisible formatting characters";

    /** Whether line feeds ({@code \n}) are allowed: true for comment bodies, notes, report details, bios. */
    boolean multiline() default false;

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

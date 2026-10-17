package com.pm.filmfuse.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** The rules of {@link SafeText}; {@link #isSafe} is usable outside bean validation too. */
public class SafeTextValidator implements ConstraintValidator<SafeText, CharSequence> {

    private boolean multiline;

    @Override
    public void initialize(SafeText annotation) {
        this.multiline = annotation.multiline();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || isSafe(value, multiline);
    }

    public static boolean isSafe(CharSequence value, boolean multiline) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (isControl(c) && !(multiline && c == '\n')) {
                return false;
            }
            if (isBidiControl(c)) {
                return false;
            }
        }
        return length == 0 || (!isZeroWidth(value.charAt(0)) && !isZeroWidth(value.charAt(length - 1)));
    }

    static boolean isControl(char c) {
        return c <= 0x1F || (c >= 0x7F && c <= 0x9F);
    }

    static boolean isBidiControl(char c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }

    static boolean isZeroWidth(char c) {
        return (c >= 0x200B && c <= 0x200D) || c == 0x2060 || c == 0xFEFF;
    }
}

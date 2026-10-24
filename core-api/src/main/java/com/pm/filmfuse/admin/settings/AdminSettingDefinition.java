package com.pm.filmfuse.admin.settings;

import com.pm.filmfuse.admin.dto.AdminSettingType;
import com.pm.filmfuse.common.exception.FieldValidationException;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One key an admin may set at runtime, with its type, safeguarded bounds and the configured default
 * (what applies while no override is stored).
 *
 * @param key          the {@code app.admin_settings.key}
 * @param group        a heading for the portal ("Catalog", "Quality", "Jobs")
 * @param label        a short human name
 * @param description  one sentence on what it changes
 * @param unit         e.g. "movies", "MB", "requests/s"; null when unitless
 * @param type         INTEGER or BOOLEAN
 * @param min          inclusive lower bound (INTEGER only)
 * @param max          inclusive upper bound (INTEGER only)
 * @param defaults     supplies the configured default, read when needed (so it follows the properties)
 */
public record AdminSettingDefinition(String key, String group, String label, String description, String unit,
                                     AdminSettingType type, Long min, Long max, Supplier<Object> defaults) {

    static final String FIELD = "value";

    public AdminSettingDefinition {
        Objects.requireNonNull(key);
        Objects.requireNonNull(type);
        Objects.requireNonNull(defaults);
        if (type == AdminSettingType.INTEGER && (min == null || max == null || min > max)) {
            throw new IllegalArgumentException("An INTEGER setting needs min <= max: " + key);
        }
    }

    public static AdminSettingDefinition integer(String key, String group, String label, String description,
                                                 String unit, long min, long max, Supplier<Object> defaultValue) {
        return new AdminSettingDefinition(key, group, label, description, unit, AdminSettingType.INTEGER, min, max,
                defaultValue);
    }

    public static AdminSettingDefinition bool(String key, String group, String label, String description,
                                              Supplier<Object> defaultValue) {
        return new AdminSettingDefinition(key, group, label, description, null, AdminSettingType.BOOLEAN, null, null,
                defaultValue);
    }

    /**
     * The value to store for an admin's input, or a 400 ({@code fieldErrors.value}). An INTEGER must be a
     * whole JSON number within [min, max] (5.0 and "5" are refused); a BOOLEAN must be true or false.
     */
    public Object validate(Object raw) {
        if (raw == null) {
            throw invalid("A value is required. To go back to the default, reset the setting.");
        }
        return switch (type) {
            case BOOLEAN -> {
                if (!(raw instanceof Boolean bool)) {
                    throw invalid("Must be true or false.");
                }
                yield bool;
            }
            case INTEGER -> {
                long value;
                if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
                    value = ((Number) raw).longValue();
                } else if (raw instanceof BigInteger big) {
                    // Far out of any bound; report the bound rather than an overflow.
                    throw invalid(boundsMessage());
                } else {
                    throw invalid("Must be a whole number.");
                }
                if (value < min || value > max) {
                    throw invalid(boundsMessage());
                }
                yield value;
            }
        };
    }

    /**
     * The effective value of a stored JSON value: a stored value of the wrong type falls back to the
     * default, and an INTEGER outside today's bounds (e.g. the ceiling was lowered since) is clamped.
     */
    public Object effective(Object stored) {
        if (stored == null) {
            return defaultValue();
        }
        return switch (type) {
            case BOOLEAN -> stored instanceof Boolean ? stored : defaultValue();
            case INTEGER -> stored instanceof Number number && !(stored instanceof Double || stored instanceof Float)
                    ? Math.max(min, Math.min(max, number.longValue()))
                    : defaultValue();
        };
    }

    /** The configured default; an INTEGER as a long. */
    public Object defaultValue() {
        Object value = defaults.get();
        return type == AdminSettingType.INTEGER && value instanceof Number number ? number.longValue() : value;
    }

    private String boundsMessage() {
        return "Must be between " + min + " and " + max + (unit == null ? "" : " " + unit) + ".";
    }

    private static FieldValidationException invalid(String message) {
        return new FieldValidationException("Validation failed", Map.of(FIELD, message));
    }
}

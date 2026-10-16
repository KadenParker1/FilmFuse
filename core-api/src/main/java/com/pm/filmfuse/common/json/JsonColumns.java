package com.pm.filmfuse.common.json;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading and writing {@code jsonb} columns from plain JDBC repositories. Bind written values with
 * {@code cast(? as jsonb)}; read them with {@code rs.getString(...)}.
 */
public final class JsonColumns {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private JsonColumns() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not serialise a jsonb value", ex);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not read a jsonb value as " + type.getSimpleName(), ex);
        }
    }

    /** A JSON object as a map; null or blank is an empty map. */
    public static Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not read a jsonb object", ex);
        }
    }
}

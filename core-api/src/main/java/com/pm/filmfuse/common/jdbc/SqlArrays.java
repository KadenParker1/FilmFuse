package com.pm.filmfuse.common.jdbc;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Postgres array literals for {@code cast(:x as int[])} / {@code real[]} and reading arrays back. */
public final class SqlArrays {

    private SqlArrays() {
    }

    /** {@code {1,2,3}}; an empty collection is {@code {}}. */
    public static String intLiteral(Collection<Integer> values) {
        return values.stream().filter(Objects::nonNull).map(String::valueOf)
                .collect(Collectors.joining(",", "{", "}"));
    }

    /** {@code {7.0,3.5}} for a {@code real[]}; null stays null. */
    public static String realLiteral(List<Double> values) {
        if (values == null) {
            return null;
        }
        return values.stream().map(v -> v == null || v.isNaN() || v.isInfinite() ? "NULL" : String.valueOf(v))
                .collect(Collectors.joining(",", "{", "}"));
    }

    /** A {@code real[]} / {@code double precision[]} as doubles; SQL null is null. */
    public static List<Double> doubles(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(v -> v == null ? null : ((Number) v).doubleValue()).toList();
    }
}

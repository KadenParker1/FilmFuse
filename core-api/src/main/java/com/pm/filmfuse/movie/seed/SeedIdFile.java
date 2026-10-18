package com.pm.filmfuse.movie.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The seeder's id file: one positive TMDB id per line. Blank lines and everything after a {@code #}
 * are ignored; surrounding whitespace is trimmed. Duplicates are dropped, first occurrence wins.
 * Anything else is an error naming the line - a malformed file is a mistake worth stopping for,
 * not something to half-seed from. {@code tools/catalog/movielens_tmdb_ids.py} writes one from
 * MovieLens' {@code links.csv}.
 */
public final class SeedIdFile {

    private SeedIdFile() {
    }

    public static List<Integer> read(Path path) {
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (NoSuchFileException ex) {
            throw new IllegalArgumentException("Catalog seed id file not found: " + path.toAbsolutePath(), ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read catalog seed id file " + path, ex);
        }
    }

    public static List<Integer> parse(List<String> lines) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int comment = line.indexOf('#');
            String value = (comment >= 0 ? line.substring(0, comment) : line).strip();
            if (value.isEmpty()) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Line " + (i + 1) + " of the id file is not a TMDB id: " + value);
            }
            if (id < 1) {
                throw new IllegalArgumentException("Line " + (i + 1) + " of the id file is not a TMDB id: " + value);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }
}

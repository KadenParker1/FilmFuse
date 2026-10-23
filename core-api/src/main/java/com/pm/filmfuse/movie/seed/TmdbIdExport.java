package com.pm.filmfuse.movie.seed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TMDB's daily movie id export ({@code movie_ids_MM_DD_YYYY.json.gz}: gzip, one JSON object per line,
 * {@code {"adult":false,"id":123,"original_title":"…","popularity":1.23,"video":false}}). Streams the
 * file, keeps the {@code max-movies} most popular non-adult, non-video entries at or above
 * {@code min-popularity} in a bounded heap (never the whole file in memory) and returns their ids,
 * most popular first (ties: lower id first).
 *
 * <p>The file is untrusted input: a line over {@code max-line-length} bytes, invalid JSON, or an entry
 * without a positive integer {@code id}, a finite non-negative {@code popularity} and
 * {@code adult: false} is ignored (counted malformed / filtered); reading stops after
 * {@code max-lines} lines or {@code max-bytes} uncompressed bytes.
 */
public class TmdbIdExport {

    private static final Logger log = LoggerFactory.getLogger(TmdbIdExport.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("MM_dd_yyyy");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExportFetcher fetcher;
    private final CatalogSeedProperties.Export settings;

    public TmdbIdExport(ExportFetcher fetcher, CatalogSeedProperties.Export settings) {
        this.fetcher = fetcher;
        this.settings = settings;
    }

    /** No export file for any of the tried dates. */
    public static class ExportUnavailableException extends RuntimeException {
        ExportUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * What one file gave.
     *
     * @param ids       kept ids, most popular first (never modified)
     * @param lines     lines read
     * @param malformed lines ignored as malformed (too long, not JSON, missing / wrong-typed fields)
     * @param filtered  well-formed entries dropped (adult, video, below min-popularity)
     * @param truncated whether reading stopped at max-lines / max-bytes
     */
    public record Ids(LocalDate date, int[] ids, long lines, long malformed, long filtered, boolean truncated) {

        public int size() {
            return ids.length;
        }
    }

    public URI uri(LocalDate date) {
        return URI.create(settings.baseUrl() + "/movie_ids_" + FILE_DATE.format(date) + ".json.gz");
    }

    /**
     * Today's file, else (on a 404) the previous day's, up to {@code fallback-days} back.
     *
     * @throws ExportUnavailableException none of those dates has a file
     * @throws IOException                download or decompression failed
     */
    public Ids load(LocalDate today) throws IOException {
        for (int back = 0; back <= settings.fallbackDays(); back++) {
            LocalDate date = today.minusDays(back);
            URI uri = uri(date);
            Optional<InputStream> body = fetcher.open(uri);
            if (body.isEmpty()) {
                log.info("TMDB export {} not available (yet)", uri);
                continue;
            }
            try (InputStream in = body.get()) {
                Ids ids = parse(date, new GZIPInputStream(new BufferedInputStream(in, 64 * 1024), 64 * 1024));
                log.info("TMDB export {}: {} lines, {} ids kept, {} filtered, {} malformed{}", uri, ids.lines(),
                        ids.size(), ids.filtered(), ids.malformed(), ids.truncated() ? " (truncated at the limit)" : "");
                return ids;
            }
        }
        throw new ExportUnavailableException("no TMDB export file for " + today.minusDays(settings.fallbackDays())
                + " .. " + today + " at " + settings.baseUrl());
    }

    /** Parses an already-decompressed stream (newline-delimited JSON). Does not close it. */
    Ids parse(LocalDate date, InputStream in) throws IOException {
        TopN top = new TopN(settings.maxMovies());
        byte[] line = new byte[settings.maxLineLength()];
        int length = 0;
        boolean overflow = false;
        long lines = 0;
        long bytes = 0;
        long malformed = 0;
        long filtered = 0;
        boolean truncated = false;
        byte[] buffer = new byte[64 * 1024];
        outer:
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            for (int i = 0; i < read; i++) {
                if (++bytes > settings.maxBytes()) {
                    truncated = true;
                    break outer;
                }
                byte b = buffer[i];
                if (b != '\n') {
                    if (length < line.length) {
                        line[length++] = b;
                    } else {
                        overflow = true;
                    }
                    continue;
                }
                lines++;
                switch (overflow ? Entry.MALFORMED : accept(line, length, top)) {
                    case MALFORMED -> malformed++;
                    case FILTERED -> filtered++;
                    default -> { }
                }
                length = 0;
                overflow = false;
                if (lines >= settings.maxLines()) {
                    truncated = true;
                    break outer;
                }
            }
        }
        if (!truncated && (length > 0 || overflow)) {
            // A last line without a trailing newline.
            lines++;
            switch (overflow ? Entry.MALFORMED : accept(line, length, top)) {
                case MALFORMED -> malformed++;
                case FILTERED -> filtered++;
                default -> { }
            }
        }
        if (truncated) {
            log.warn("TMDB export: stopped reading after {} lines / {} bytes (max-lines {}, max-bytes {})", lines,
                    bytes, settings.maxLines(), settings.maxBytes());
        }
        return new Ids(date, top.drainMostPopularFirst(), lines, malformed, filtered, truncated);
    }

    private enum Entry { KEPT, FILTERED, MALFORMED }

    private Entry accept(byte[] line, int length, TopN top) {
        int start = 0;
        while (start < length && Character.isWhitespace(line[start])) {
            start++;
        }
        if (start == length) {
            return Entry.MALFORMED;
        }
        JsonNode node;
        try {
            node = JSON.readTree(line, start, length - start);
        } catch (JacksonException ex) {
            return Entry.MALFORMED;
        }
        if (node == null || !node.isObject()) {
            return Entry.MALFORMED;
        }
        JsonNode id = node.get("id");
        JsonNode popularity = node.get("popularity");
        JsonNode adult = node.get("adult");
        JsonNode video = node.get("video");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() < 1
                || popularity == null || !popularity.isNumber() || !Double.isFinite(popularity.doubleValue())
                || popularity.doubleValue() < 0
                || adult == null || !adult.isBoolean()
                || (video != null && !video.isBoolean() && !video.isNull())) {
            return Entry.MALFORMED;
        }
        if (adult.booleanValue() || (video != null && video.isBoolean() && video.booleanValue())
                || popularity.doubleValue() < settings.minPopularity()) {
            return Entry.FILTERED;
        }
        top.offer(id.intValue(), popularity.doubleValue());
        return Entry.KEPT;
    }

    /**
     * The {@code capacity} most popular (id, popularity) pairs: a binary min-heap on primitive arrays
     * whose root is the least popular kept entry (ties: the higher id counts as less popular).
     */
    static final class TopN {

        private final int capacity;
        private int[] ids = new int[16];
        private double[] popularity = new double[16];
        private int size;

        TopN(int capacity) {
            this.capacity = capacity;
        }

        /** True when a ranks below b. */
        private boolean worse(int a, int b) {
            return popularity[a] < popularity[b] || (popularity[a] == popularity[b] && ids[a] > ids[b]);
        }

        void offer(int id, double pop) {
            if (size < capacity) {
                if (size == ids.length) {
                    int grown = (int) Math.min(capacity, Math.max(16L, ids.length * 2L));
                    ids = java.util.Arrays.copyOf(ids, grown);
                    popularity = java.util.Arrays.copyOf(popularity, grown);
                }
                ids[size] = id;
                popularity[size] = pop;
                siftUp(size++);
                return;
            }
            // Full: replace the root only if the newcomer ranks above it.
            if (pop < popularity[0] || (pop == popularity[0] && id > ids[0])) {
                return;
            }
            ids[0] = id;
            popularity[0] = pop;
            siftDown(0);
        }

        private void siftUp(int i) {
            while (i > 0) {
                int parent = (i - 1) / 2;
                if (!worse(i, parent)) {
                    return;
                }
                swap(i, parent);
                i = parent;
            }
        }

        private void siftDown(int i) {
            while (true) {
                int left = 2 * i + 1;
                if (left >= size) {
                    return;
                }
                int child = left + 1 < size && worse(left + 1, left) ? left + 1 : left;
                if (!worse(child, i)) {
                    return;
                }
                swap(i, child);
                i = child;
            }
        }

        private void swap(int a, int b) {
            int id = ids[a];
            ids[a] = ids[b];
            ids[b] = id;
            double pop = popularity[a];
            popularity[a] = popularity[b];
            popularity[b] = pop;
        }

        /** Empties the heap: ids most popular first, a repeated id kept at its best position only. */
        int[] drainMostPopularFirst() {
            int[] ordered = new int[size];
            for (int out = size - 1; out >= 0; out--) {
                ordered[out] = ids[0];
                size--;
                if (size > 0) {
                    ids[0] = ids[size];
                    popularity[0] = popularity[size];
                    siftDown(0);
                }
            }
            Set<Integer> seen = new HashSet<>();
            int kept = 0;
            for (int id : ordered) {
                if (seen.add(id)) {
                    ordered[kept++] = id;
                }
            }
            return kept == ordered.length ? ordered : java.util.Arrays.copyOf(ordered, kept);
        }
    }
}

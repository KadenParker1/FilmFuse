package com.pm.filmfuse.movie.seed;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TMDB's daily id export: streaming gzip parse, untrusted-input limits, filters, top-N and date fallback. */
class TmdbIdExportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private static CatalogSeedProperties.Export settings(Double minPopularity, Integer maxMovies, Integer maxLines,
                                                         Integer maxLineLength, Long maxBytes) {
        return new CatalogSeedProperties.Export("https://exports.test/p/exports/", minPopularity, maxMovies, 2,
                maxLines, maxLineLength, maxBytes);
    }

    private static CatalogSeedProperties.Export defaults() {
        return settings(null, null, null, null, null);
    }

    private static String line(int id, double popularity) {
        return "{\"adult\":false,\"id\":" + id + ",\"original_title\":\"Movie " + id + "\",\"popularity\":" + popularity
                + ",\"video\":false}";
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static TmdbIdExport.Ids parse(CatalogSeedProperties.Export settings, String content) throws IOException {
        return new TmdbIdExport(uri -> Optional.empty(), settings)
                .parse(TODAY, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Serves gzip files by URI; anything else is a 404 (empty). Records what was asked for. */
    private static final class FakeFiles implements ExportFetcher {
        final Map<String, byte[]> files;
        final List<String> requested = new ArrayList<>();

        FakeFiles(Map<String, byte[]> files) {
            this.files = files;
        }

        @Override
        public Optional<InputStream> open(URI uri) {
            requested.add(uri.toString());
            byte[] file = files.get(uri.toString());
            return file == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(file));
        }
    }

    @Test
    void theFileNameIsTmdbsDatePattern() {
        TmdbIdExport export = new TmdbIdExport(uri -> Optional.empty(), defaults());
        assertThat(export.uri(LocalDate.of(2026, 3, 7)))
                .hasToString("https://exports.test/p/exports/movie_ids_03_07_2026.json.gz");
        assertThat(new TmdbIdExport(uri -> Optional.empty(), new CatalogSeedProperties.Export(null, null, null, null,
                null, null, null)).uri(TODAY))
                .hasToString("https://files.tmdb.org/p/exports/movie_ids_10_22_2026.json.gz");
    }

    @Test
    void aGzipStreamIsParsedAndSortedByPopularityThenId() throws Exception {
        String content = String.join("\n", line(10, 1.5), line(11, 9.25), line(12, 1.5), line(13, 0.6)) + "\n";
        FakeFiles files = new FakeFiles(Map.of(
                "https://exports.test/p/exports/movie_ids_10_22_2026.json.gz", gzip(content)));

        TmdbIdExport.Ids ids = new TmdbIdExport(files, defaults()).load(TODAY);

        assertThat(ids.date()).isEqualTo(TODAY);
        assertThat(ids.ids()).containsExactly(11, 10, 12, 13);
        assertThat(ids.lines()).isEqualTo(4);
        assertThat(ids.malformed()).isZero();
        assertThat(ids.truncated()).isFalse();
    }

    @Test
    void adultVideoAndUnpopularEntriesAreFilteredOut() throws Exception {
        String content = String.join("\n",
                line(1, 5),
                "{\"adult\":true,\"id\":2,\"popularity\":50,\"video\":false}",
                "{\"adult\":false,\"id\":3,\"popularity\":50,\"video\":true}",
                "{\"adult\":false,\"id\":4,\"popularity\":0.4,\"video\":false}",
                // No video flag at all is fine.
                "{\"adult\":false,\"id\":5,\"popularity\":2}");

        TmdbIdExport.Ids ids = parse(settings(0.5, null, null, null, null), content);

        assertThat(ids.ids()).containsExactly(1, 5);
        assertThat(ids.filtered()).isEqualTo(3);
        assertThat(ids.malformed()).isZero();
        assertThat(ids.lines()).isEqualTo(5);
    }

    @Test
    void malformedAndOverlongLinesAreIgnored() throws Exception {
        String content = String.join("\n",
                line(1, 3),
                "not json",
                "",
                "[1,2,3]",
                "{\"adult\":false,\"id\":\"7\",\"popularity\":3}",
                "{\"adult\":false,\"id\":7.5,\"popularity\":3}",
                "{\"adult\":false,\"id\":-7,\"popularity\":3}",
                "{\"adult\":false,\"id\":99999999999,\"popularity\":3}",
                "{\"adult\":false,\"id\":8,\"popularity\":\"high\"}",
                "{\"adult\":false,\"id\":8,\"popularity\":-1}",
                "{\"adult\":\"no\",\"id\":8,\"popularity\":3}",
                "{\"id\":8,\"popularity\":3}",
                "{\"adult\":false,\"id\":8,\"popularity\":3,\"video\":\"yes\"}",
                "{\"adult\":false,\"id\":9,\"popularity\":3,\"original_title\":\"" + "x".repeat(200) + "\"}",
                "{\"adult\":false,\"id\":10,\"pop",
                "  " + line(11, 2) + "\r",
                line(12, 1));

        TmdbIdExport.Ids ids = parse(settings(null, null, null, 100, null), content);

        assertThat(ids.ids()).containsExactly(1, 11, 12);
        assertThat(ids.malformed()).isEqualTo(14);
        assertThat(ids.lines()).isEqualTo(17);
    }

    @Test
    void onlyTheTopNMostPopularAreKept() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int id = 1; id <= 1_000; id++) {
            content.append(line(id, (id * 7919) % 1_000 / 10.0)).append('\n');
        }

        TmdbIdExport.Ids ids = parse(settings(null, 5, null, null, null), content.toString());

        // popularity(id) = (id * 7919 mod 1000) / 10; the five highest are 99.9, 99.8, ... 99.5.
        assertThat(ids.size()).isEqualTo(5);
        List<Integer> expected = IntStream.rangeClosed(1, 1_000).boxed()
                .sorted(Comparator.comparingDouble((Integer id) -> -((id * 7919) % 1_000 / 10.0))
                        .thenComparingInt(id -> id))
                .limit(5).toList();
        assertThat(Arrays.stream(ids.ids()).boxed().toList()).isEqualTo(expected);
    }

    @Test
    void theHeapMatchesAFullSortOnRandomInputWithTiesAndDuplicates() {
        Random random = new Random(42);
        for (int round = 0; round < 50; round++) {
            int capacity = 1 + random.nextInt(40);
            TmdbIdExport.TopN top = new TmdbIdExport.TopN(capacity);
            List<double[]> offered = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int id = 1 + random.nextInt(150);
                double popularity = random.nextInt(20) / 2.0;
                top.offer(id, popularity);
                offered.add(new double[] {id, popularity});
            }
            List<Integer> expected = offered.stream()
                    .sorted(Comparator.comparingDouble((double[] e) -> -e[1]).thenComparingDouble(e -> e[0]))
                    .limit(capacity)
                    .map(e -> (int) e[0])
                    .distinct()
                    .toList();
            assertThat(Arrays.stream(top.drainMostPopularFirst()).boxed().toList()).isEqualTo(expected);
        }
    }

    @Test
    void readingStopsAtMaxLines() throws Exception {
        String content = IntStream.rangeClosed(1, 10).mapToObj(id -> line(id, id)).reduce((a, b) -> a + "\n" + b)
                .orElseThrow();

        TmdbIdExport.Ids ids = parse(settings(null, null, 4, null, null), content);

        assertThat(ids.truncated()).isTrue();
        assertThat(ids.lines()).isEqualTo(4);
        assertThat(ids.ids()).containsExactly(4, 3, 2, 1);
    }

    @Test
    void readingStopsAtMaxBytesEvenWithoutANewline() throws Exception {
        // One endless line (a decompression bomb would look like this): never buffered, stopped at the cap.
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };
        TmdbIdExport export = new TmdbIdExport(uri -> Optional.empty(), settings(null, null, null, null, 100_000L));

        TmdbIdExport.Ids ids = export.parse(TODAY, endless);

        assertThat(ids.truncated()).isTrue();
        assertThat(ids.size()).isZero();
    }

    @Test
    void aMissingFileFallsBackToThePreviousDays() throws Exception {
        FakeFiles files = new FakeFiles(Map.of(
                "https://exports.test/p/exports/movie_ids_10_20_2026.json.gz", gzip(line(5, 1) + "\n")));

        TmdbIdExport.Ids ids = new TmdbIdExport(files, defaults()).load(TODAY);

        assertThat(ids.date()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(ids.ids()).containsExactly(5);
        assertThat(files.requested).containsExactly(
                "https://exports.test/p/exports/movie_ids_10_22_2026.json.gz",
                "https://exports.test/p/exports/movie_ids_10_21_2026.json.gz",
                "https://exports.test/p/exports/movie_ids_10_20_2026.json.gz");
    }

    @Test
    void noFileWithinTheFallbackDaysIsAnError() {
        FakeFiles files = new FakeFiles(Map.of());

        assertThatThrownBy(() -> new TmdbIdExport(files, defaults()).load(TODAY))
                .isInstanceOf(TmdbIdExport.ExportUnavailableException.class)
                .hasMessageContaining("2026-10-20");
        assertThat(files.requested).hasSize(3);
    }

    @Test
    void aCorruptGzipIsAnIoError() {
        FakeFiles files = new FakeFiles(Map.of(
                "https://exports.test/p/exports/movie_ids_10_22_2026.json.gz", "not gzip".getBytes()));

        assertThatThrownBy(() -> new TmdbIdExport(files, defaults()).load(TODAY)).isInstanceOf(IOException.class);
    }

    @Test
    void theHttpFetcherStreamsA200AndTreats404And403AsMissing() throws Exception {
        byte[] file = gzip(line(1, 1) + "\n");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, file.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(file);
            }
        });
        server.createContext("/forbidden", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ExportFetcher http = ExportFetcher.http();

            try (InputStream body = http.open(URI.create(base + "/ok")).orElseThrow()) {
                assertThat(body.readAllBytes()).isEqualTo(file);
            }
            assertThat(http.open(URI.create(base + "/missing"))).isEmpty();
            assertThat(http.open(URI.create(base + "/forbidden"))).isEmpty();
            assertThatThrownBy(() -> http.open(URI.create(base + "/broken")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("500");
        } finally {
            server.stop(0);
        }
    }
}

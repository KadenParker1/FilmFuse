package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code filmfuse.catalog.seed.*} - the catalog seeder ({@link CatalogSeedService}).
 *
 * @param runOnStartup      run a full seed when the application starts ({@link CatalogSeedRunner}); the
 *                          {@code seed} profile sets it, together with no web server, and exits afterwards
 * @param popularPages      popular pages 1..n of a full seed (default and maximum 500 = TMDB's limit,
 *                          about 10,000 movies; 0 = none, e.g. an id-file-only run)
 * @param idFile            optional file of TMDB ids, one per line ({@code #} comments, blanks ignored),
 *                          seeded after the popular pages; blank = none
 * @param requestsPerSecond catalog requests per second, retries included (default 20; 40 is safe-ish -
 *                          TMDB allows roughly 50 per second per IP)
 * @param maxRetries        retries of a failing request (5xx, 429, timeout) with exponential backoff
 *                          before the movie is counted failed (default 3)
 * @param initialBackoff    the first retry's delay, doubled for each further retry (default 1 s)
 * @param refreshAfter      a movie whose details were fetched less than this long ago is skipped
 *                          (default 7 days, as {@code MovieService}'s details TTL)
 * @param authFailureLimit  consecutive rejected-token (401) responses after which the run stops (default 3)
 * @param progressEvery     log progress every n candidates (default 250)
 * @param topUp             the nightly top-up
 * @param sources           what a full seed ({@code seedAll}) reads, in this order: {@code popular},
 *                          {@code ids}, {@code export}, {@code discover} (default {@code popular,ids})
 * @param maxDatabaseMb     stop the run (ABORTED, cursors kept) once {@code pg_database_size} exceeds
 *                          this many MB, checked before every page / batch (default 450 - Supabase's
 *                          free tier allows 500 MB per project; 0 = off)
 * @param export            the TMDB daily id export source
 * @param discover          the discover-by-year source
 * @param quality           the quality bar movies of the long-tail sources must pass to be stored
 */
@ConfigurationProperties(prefix = "filmfuse.catalog.seed")
public record CatalogSeedProperties(
        Boolean runOnStartup,
        Integer popularPages,
        String idFile,
        Double requestsPerSecond,
        Integer maxRetries,
        Duration initialBackoff,
        Duration refreshAfter,
        Integer authFailureLimit,
        Integer progressEvery,
        TopUp topUp,
        List<Source> sources,
        Integer maxDatabaseMb,
        Export export,
        Discover discover,
        Quality quality) {

    public static final String DEFAULT_CRON = "0 0 4 * * *";
    public static final int DEFAULT_MAX_DATABASE_MB = 450;

    /** Where a full seed takes movie ids from. */
    public enum Source { POPULAR, IDS, EXPORT, DISCOVER }

    @ConstructorBinding
    public CatalogSeedProperties {
        runOnStartup = runOnStartup != null && runOnStartup;
        popularPages = popularPages == null ? MovieCatalogClient.MAX_PAGE
                : Math.max(0, Math.min(popularPages, MovieCatalogClient.MAX_PAGE));
        idFile = idFile == null || idFile.isBlank() ? null : idFile.trim();
        requestsPerSecond = requestsPerSecond == null || !(requestsPerSecond > 0) ? 20d : requestsPerSecond;
        maxRetries = maxRetries == null || maxRetries < 0 ? 3 : maxRetries;
        initialBackoff = initialBackoff == null || initialBackoff.isNegative() ? Duration.ofSeconds(1) : initialBackoff;
        refreshAfter = refreshAfter == null || refreshAfter.isNegative() ? Duration.ofDays(7) : refreshAfter;
        authFailureLimit = authFailureLimit == null || authFailureLimit < 1 ? 3 : authFailureLimit;
        progressEvery = progressEvery == null || progressEvery < 1 ? 250 : progressEvery;
        topUp = topUp == null ? new TopUp(null, null, null) : topUp;
        sources = sources == null || sources.stream().allMatch(Objects::isNull)
                ? List.of(Source.POPULAR, Source.IDS)
                : List.copyOf(sources.stream().filter(Objects::nonNull).distinct().toList());
        maxDatabaseMb = maxDatabaseMb == null || maxDatabaseMb < 0 ? DEFAULT_MAX_DATABASE_MB : maxDatabaseMb;
        export = export == null ? new Export(null, null, null, null, null, null, null) : export;
        discover = discover == null ? new Discover(null, null, null, null) : discover;
        quality = quality == null ? new Quality(null, null, null, null, null, null, null) : quality;
    }

    /** The pre-bulk-seeding shape: default sources, size guard, export, discover and quality settings. */
    public CatalogSeedProperties(Boolean runOnStartup, Integer popularPages, String idFile, Double requestsPerSecond,
                                 Integer maxRetries, Duration initialBackoff, Duration refreshAfter,
                                 Integer authFailureLimit, Integer progressEvery, TopUp topUp) {
        this(runOnStartup, popularPages, idFile, requestsPerSecond, maxRetries, initialBackoff, refreshAfter,
                authFailureLimit, progressEvery, topUp, null, null, null, null, null);
    }

    public boolean uses(Source source) {
        return sources.contains(source);
    }

    /**
     * {@code filmfuse.catalog.seed.top-up.*}: re-seeds popular pages 1..{@code pages} to pick up new
     * releases. Not resumable (it always starts at page 1) and never reads the id file.
     *
     * @param enabled whether the job is scheduled (default true; false under the test and seed profiles)
     * @param cron    when, UTC (default 04:00 daily)
     * @param pages   popular pages (default {@value #DEFAULT_PAGES}, about 1,000 movies)
     */
    public record TopUp(Boolean enabled, String cron, Integer pages) {

        public static final int DEFAULT_PAGES = 50;

        public TopUp {
            enabled = enabled == null || enabled;
            cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron;
            pages = pages == null || pages < 1 ? DEFAULT_PAGES : Math.min(pages, MovieCatalogClient.MAX_PAGE);
        }
    }

    /**
     * {@code filmfuse.catalog.seed.export.*}: TMDB's daily id export
     * ({@code <base-url>/movie_ids_MM_DD_YYYY.json.gz}, no auth). The file is untrusted input: lines
     * longer than {@code max-line-length} or not a well-formed entry are ignored, and reading stops
     * after {@code max-lines} lines or {@code max-bytes} uncompressed bytes.
     *
     * @param baseUrl       where the files live (default {@code https://files.tmdb.org/p/exports})
     * @param minPopularity entries below this popularity are dropped (default 0 = keep all; the top-N cut
     *                      does the real selection)
     * @param maxMovies     keep the {@code max-movies} most popular ids (default 150,000)
     * @param fallbackDays  a file appears around 08:00 UTC, so on a 404 try up to this many earlier days
     *                      (default 3)
     * @param maxLines      stop reading after this many lines (default 5,000,000; the real file has ~1.1M)
     * @param maxLineLength longer lines are ignored as malformed (default 1,024 bytes)
     * @param maxBytes      stop reading after this many uncompressed bytes (default 512 MiB)
     */
    public record Export(String baseUrl, Double minPopularity, Integer maxMovies, Integer fallbackDays,
                         Integer maxLines, Integer maxLineLength, Long maxBytes) {

        public static final String DEFAULT_BASE_URL = "https://files.tmdb.org/p/exports";

        public Export {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            minPopularity = minPopularity == null || !Double.isFinite(minPopularity) || minPopularity < 0 ? 0d
                    : minPopularity;
            maxMovies = maxMovies == null || maxMovies < 1 ? 150_000 : maxMovies;
            fallbackDays = fallbackDays == null || fallbackDays < 0 ? 3 : Math.min(fallbackDays, 30);
            maxLines = maxLines == null || maxLines < 1 ? 5_000_000 : maxLines;
            maxLineLength = maxLineLength == null || maxLineLength < 16 ? 1_024 : maxLineLength;
            maxBytes = maxBytes == null || maxBytes < 1 ? 512L * 1024 * 1024 : maxBytes;
        }
    }

    /**
     * {@code filmfuse.catalog.seed.discover.*}: {@code /discover/movie} per primary release year,
     * most votes first.
     *
     * @param fromYear     first year (default 1920)
     * @param toYear       last year (default null = the current year, UTC)
     * @param pagesPerYear pages per year, 20 movies each (default 10; at most 500, TMDB's limit)
     * @param minVotes     {@code vote_count.gte} (default 10)
     */
    public record Discover(Integer fromYear, Integer toYear, Integer pagesPerYear, Integer minVotes) {

        public Discover {
            fromYear = fromYear == null || fromYear < 1870 ? 1920 : fromYear;
            toYear = toYear == null || toYear < 1870 ? null : toYear;
            pagesPerYear = pagesPerYear == null || pagesPerYear < 1 ? 10
                    : Math.min(pagesPerYear, MovieCatalogClient.MAX_PAGE);
            minVotes = minVotes == null || minVotes < 0 ? 10 : minVotes;
        }
    }

    /**
     * {@code filmfuse.catalog.seed.quality.*}: after a details fetch, a movie from one of
     * {@code sources} is stored only if it passes; otherwise it is counted {@code rejectedQuality},
     * recorded in {@code app.catalog_seed_rejects} and not fetched again for {@code reject-ttl}. A row
     * already in {@code app.movies} is left as it is. Popular pages and the id file are not filtered
     * by default: TMDB's popular list is curated already and brand-new releases have few votes.
     *
     * @param enabled         the filter at all (default true)
     * @param sources         which sources it applies to (default {@code export,discover})
     * @param requirePoster   a poster path (default true)
     * @param requireRuntime  a runtime above 0 (default true)
     * @param requireOverview a non-blank overview (default true)
     * @param minVotes        at least this many TMDB votes (default 5; an unknown count fails when above 0)
     * @param rejectTtl       how long a rejected id is skipped (default 30 days)
     */
    public record Quality(Boolean enabled, List<Source> sources, Boolean requirePoster, Boolean requireRuntime,
                          Boolean requireOverview, Integer minVotes, Duration rejectTtl) {

        public Quality {
            enabled = enabled == null || enabled;
            sources = sources == null ? List.of(Source.EXPORT, Source.DISCOVER)
                    : List.copyOf(sources.stream().filter(Objects::nonNull).distinct().toList());
            requirePoster = requirePoster == null || requirePoster;
            requireRuntime = requireRuntime == null || requireRuntime;
            requireOverview = requireOverview == null || requireOverview;
            minVotes = minVotes == null || minVotes < 0 ? 5 : minVotes;
            rejectTtl = rejectTtl == null || rejectTtl.isNegative() ? Duration.ofDays(30) : rejectTtl;
        }

        public boolean appliesTo(Source source) {
            return enabled && sources.contains(source);
        }
    }
}

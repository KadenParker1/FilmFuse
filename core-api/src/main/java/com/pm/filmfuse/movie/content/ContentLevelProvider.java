package com.pm.filmfuse.movie.content;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.reco.dto.ContentDimension;
import java.util.Map;

/**
 * Derives per-dimension content levels (violence, sexual, language, drugs, frightening) for a
 * movie. v1 is {@link KeywordContentLevelProvider} (TMDB keywords + the certification); a licensed
 * parental-guide dataset can replace it behind this interface later.
 */
public interface ContentLevelProvider {

    /** The {@code movie_content_levels.source} value for rows this provider writes. */
    String source();

    /**
     * The levels this provider can say something about. A dimension left out is unknown - the
     * comfort filter then treats it conservatively - so never guess a level without evidence.
     */
    Map<ContentDimension, ContentLevelEstimate> estimate(CatalogMovie movie);
}

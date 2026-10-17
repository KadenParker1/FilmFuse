package com.pm.filmfuse.rating.repository;

import static com.pm.filmfuse.social.service.SocialGraphService.sqlAreFriends;
import static com.pm.filmfuse.social.service.SocialGraphService.sqlBlockedEitherWay;

/**
 * <strong>The</strong> rule for who may see a rating - the only copy. Every read of other people's
 * ratings ({@code /feed}, {@code /movies/{id}/ratings}, {@code /users/{id}/ratings}) and the like
 * check ({@code POST /ratings/{id}/like}) embed {@link #visibleTo}; nothing else decides
 * visibility. Change the rule here and every endpoint follows (FeedIntegrationTest covers each
 * branch through each endpoint).
 *
 * <p>A rating {@code r} by author {@code a} is visible to the viewer {@code :viewer} iff
 * <ol>
 *   <li>it is not a calibration swipe ({@code source <> 'onboarding_swipe'}) - never shown anywhere;</li>
 *   <li>the author is not suspended (deleted authors have no rows: FK cascade);</li>
 *   <li>neither the author nor the viewer has blocked the other;</li>
 *   <li>and it is {@code PUBLIC}, or {@code FRIENDS} and the viewer is the author or a friend of
 *       the author, or - only where {@code includeOwnPrivate} - {@code PRIVATE} and the viewer is the
 *       author (the caller's own profile and their likes).</li>
 * </ol>
 *
 * <p>The fragment expects the query to alias {@code app.ratings} as {@code r} and the author's
 * {@code app.profiles} row as {@code a}, and to bind {@code :viewer}. Graph tests come from
 * {@link com.pm.filmfuse.social.service.SocialGraphService}'s SQL fragments, so graph-table SQL
 * stays in the social package. Rule 1 is a plain top-level conjunct so the partial index
 * {@code ratings_public_updated_idx} stays usable.
 */
public final class RatingVisibilitySql {

    private RatingVisibilitySql() {
    }

    /**
     * SQL boolean: {@code r} is visible to {@code :viewer}.
     *
     * @param includeOwnPrivate whether the viewer's own PRIVATE ratings count as visible (true only
     *                          for the viewer's own profile list and the like check)
     */
    public static String visibleTo(boolean includeOwnPrivate) {
        String ownPrivate = includeOwnPrivate ? "\n         or (r.visibility = 'PRIVATE' and r.user_id = :viewer)" : "";
        return """
                (r.source <> 'onboarding_swipe'
                 and a.status <> 'SUSPENDED'
                 and not %s
                 and (r.visibility = 'PUBLIC'
                      or (r.visibility = 'FRIENDS' and (r.user_id = :viewer or %s))%s))"""
                .formatted(sqlBlockedEitherWay("r.user_id", ":viewer"), sqlAreFriends("r.user_id", ":viewer"),
                        ownPrivate);
    }
}

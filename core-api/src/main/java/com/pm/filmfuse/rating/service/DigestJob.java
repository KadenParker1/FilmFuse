package com.pm.filmfuse.rating.service;

import com.pm.filmfuse.email.MailErrors;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.rating.model.FeedEntry;
import com.pm.filmfuse.rating.repository.FeedRepository;
import com.pm.filmfuse.social.repository.SocialProfileRepository.Contact;
import com.pm.filmfuse.social.service.NotificationEmailSender;
import com.pm.filmfuse.social.service.NotificationEmailSender.Draft;
import com.pm.filmfuse.social.service.SocialEmailNotifier;
import com.pm.filmfuse.user.model.NotificationType;
import com.pm.filmfuse.user.service.NotificationSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The weekly digest: every user who opted in ({@code weeklyDigest}, off by default) gets their
 * friends' top {@value #SIZE} ratings of the last {@value #WINDOW_DAYS} days that they can see
 * (RatingVisibilitySql), best score first. Users with nothing to show get no email. Notes are not
 * quoted (spoilers); each line links to the rating. Scheduled weekly by {@link DigestConfig}.
 */
@Component
public class DigestJob {

    public static final int SIZE = 5;
    public static final int WINDOW_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(DigestJob.class);

    private final NotificationSettingsService settings;
    private final FeedRepository feed;
    private final MovieSummaries movieSummaries;
    private final NotificationEmailSender sender;
    private final Clock clock;

    public DigestJob(NotificationSettingsService settings, FeedRepository feed, MovieSummaries movieSummaries,
                     NotificationEmailSender sender, Clock clock) {
        this.settings = settings;
        this.feed = feed;
        this.movieSummaries = movieSummaries;
        this.sender = sender;
        this.clock = clock;
    }

    /** @return how many digests were sent */
    public int sendAll() {
        Instant since = clock.instant().minus(Duration.ofDays(WINDOW_DAYS));
        int sent = 0;
        List<UUID> recipients = settings.digestRecipients();
        for (UUID userId : recipients) {
            try {
                List<FeedEntry> top = feed.topFriendRatings(userId, since, SIZE);
                if (top.isEmpty()) {
                    continue;
                }
                Draft draft = draft(top, movieSummaries.of(top.stream().map(FeedEntry::movieId).toList()));
                if (sender.send(userId, NotificationType.WEEKLY_DIGEST, draft)) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.warn("Weekly digest failed for user {}: {}", userId, MailErrors.describe(ex));
            }
        }
        log.info("Weekly digest: {} opted in, {} sent", recipients.size(), sent);
        return sent;
    }

    private Draft draft(List<FeedEntry> top, Map<Integer, MovieSummaryDTO> movies) {
        StringBuilder body = new StringBuilder("Your friends' top ratings on FilmFuse this week:\n\n");
        for (FeedEntry entry : top) {
            MovieSummaryDTO movie = movies.get(entry.movieId());
            String name = SocialEmailNotifier.nameOf(new Contact(entry.author().id(), entry.author().username(),
                    entry.author().displayName(), null));
            body.append("- %s rated %s %d/10\n  %s\n".formatted(name, movie == null ? "a movie" : movie.title(),
                    entry.score(), sender.webUrl() + "/ratings/" + entry.id()));
        }
        body.append("\nYou get this because the weekly digest is on. Turn it off any time in Settings > Notifications:\n")
                .append(sender.webUrl()).append("/settings/notifications\n");
        return new Draft("Your friends' top movies this week", body.toString());
    }
}

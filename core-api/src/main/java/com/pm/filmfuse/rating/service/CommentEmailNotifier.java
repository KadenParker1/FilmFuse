package com.pm.filmfuse.rating.service;

import com.pm.filmfuse.movie.model.Movie;
import com.pm.filmfuse.movie.repository.MovieRepository;
import com.pm.filmfuse.social.service.NotificationEmailSender;
import com.pm.filmfuse.social.service.NotificationEmailSender.Draft;
import com.pm.filmfuse.user.model.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails a rating's author when someone else comments on it, after the comment has committed and
 * only if the author's {@code comments} preference is on. The comment text is not quoted (it may
 * hold spoilers); the link opens the rating with its comments.
 */
@Component
public class CommentEmailNotifier {

    private final NotificationEmailSender sender;
    private final MovieRepository movies;

    public CommentEmailNotifier(NotificationEmailSender sender, MovieRepository movies) {
        this.sender = sender;
        this.movies = movies;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommented(RatingCommentedEvent event) {
        if (event.ratingAuthorId().equals(event.commenterId())) {
            return;
        }
        sender.send(java.util.List.of(event.ratingAuthorId()), NotificationType.COMMENTS, id -> {
            String name = sender.nameOf(event.commenterId());
            String title = movies.findById(event.movieId()).map(Movie::getTitle).orElse("a movie");
            return new Draft("%s commented on your rating of %s".formatted(name, title), """
                    %s commented on your rating of %s on FilmFuse.

                    Read and reply:
                    %s
                    """.formatted(name, title, sender.webUrl() + "/ratings/" + event.ratingId()));
        });
    }
}

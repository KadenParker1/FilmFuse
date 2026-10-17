package com.pm.filmfuse.user.service;

import com.pm.filmfuse.user.dto.NotificationSettingsDTO;
import com.pm.filmfuse.user.model.NotificationType;
import com.pm.filmfuse.user.repository.NotificationSettingsRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email notification preferences: the caller's own (GET/PUT) and, for every notifier, who among a
 * set of recipients still wants a given kind of email.
 */
@Service
public class NotificationSettingsService {

    private final NotificationSettingsRepository repository;
    private final Clock clock;

    public NotificationSettingsService(NotificationSettingsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsDTO get(UUID userId) {
        return repository.find(userId).orElse(NotificationSettingsDTO.DEFAULTS);
    }

    /** A full replacement (PUT); every field is required (validated by the controller). */
    @Transactional
    public NotificationSettingsDTO update(UUID userId, NotificationSettingsDTO request) {
        repository.upsert(userId, request, clock.instant());
        return get(userId);
    }

    /** The ids among {@code userIds} that want {@code type} emails. */
    @Transactional(readOnly = true)
    public Set<UUID> allowing(Collection<UUID> userIds, NotificationType type) {
        return repository.allowing(userIds, type);
    }

    /** Active, opted-in users with an email address. */
    @Transactional(readOnly = true)
    public List<UUID> digestRecipients() {
        return repository.digestRecipients();
    }
}

package com.pm.filmfuse.user.service;

import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.user.dto.ComfortSettingsDTO;
import com.pm.filmfuse.user.repository.UserSettingsRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Content comfort settings: the caller's own (GET/PUT), and members' for the comfort filter. */
@Service
public class ComfortSettingsService {

    private final UserSettingsRepository repository;
    private final Clock clock;

    public ComfortSettingsService(UserSettingsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ComfortSettingsDTO get(UUID userId) {
        return toDTO(comfortOf(userId));
    }

    /** A full replacement (PUT). {@code STRONG} limits are stored as "no limit". */
    @Transactional
    public ComfortSettingsDTO update(UUID userId, ComfortSettingsDTO request) {
        ContentFilter comfort = new ContentFilter(request.maxCertification(), request.contentLimits(),
                request.allowUnrated());
        repository.upsertComfort(userId, comfort, clock.instant());
        return toDTO(comfort);
    }

    @Transactional(readOnly = true)
    public ContentFilter comfortOf(UUID userId) {
        return repository.findComfort(List.of(userId)).get(userId);
    }

    /** One entry per id; users who never saved settings get {@link ContentFilter#USER_DEFAULT}. */
    @Transactional(readOnly = true)
    public Map<UUID, ContentFilter> comfortOf(Collection<UUID> userIds) {
        return repository.findComfort(userIds);
    }

    static ComfortSettingsDTO toDTO(ContentFilter comfort) {
        return new ComfortSettingsDTO(comfort.maxCertification(), new LinkedHashMap<>(comfort.contentLimits()),
                comfort.allowUnrated());
    }
}

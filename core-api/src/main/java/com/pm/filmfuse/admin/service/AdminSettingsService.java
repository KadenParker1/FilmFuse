package com.pm.filmfuse.admin.service;

import com.pm.filmfuse.admin.dto.AdminAction;
import com.pm.filmfuse.admin.dto.AdminSettingDTO;
import com.pm.filmfuse.admin.repository.AdminSettingsRepository;
import com.pm.filmfuse.admin.repository.AdminSettingsRepository.StoredSetting;
import com.pm.filmfuse.admin.settings.AdminSettingDefinition;
import com.pm.filmfuse.admin.settings.AdminSettingsRegistry;
import com.pm.filmfuse.common.exception.BadRequestException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runtime settings: the registry's keys only, validated against their type and bounds, every change
 * audited in the same transaction. Readers ({@link #intValue}, {@link #boolValue}) hit the database
 * each time, so a change applies to the next job run without a redeploy or a cache to invalidate.
 */
@Service
public class AdminSettingsService {

    static final String UNKNOWN_KEY = "Unknown setting.";

    private final AdminSettingsRegistry registry;
    private final AdminSettingsRepository repository;
    private final AdminAuditLog audit;
    private final Clock clock;

    public AdminSettingsService(AdminSettingsRegistry registry, AdminSettingsRepository repository,
                                AdminAuditLog audit, Clock clock) {
        this.registry = registry;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminSettingDTO> list() {
        Map<String, StoredSetting> stored = repository.findAll();
        return registry.all().stream().map(definition -> toDTO(definition, stored.get(definition.key()))).toList();
    }

    @Transactional
    public AdminSettingDTO update(UUID adminId, String key, Object rawValue) {
        AdminSettingDefinition definition = definition(key);
        Object value = definition.validate(rawValue);
        Optional<Object> old = repository.lockValue(key);
        repository.upsert(key, value, adminId, clock.instant());
        audit.record(adminId, AdminAction.SETTING_UPDATE, key, old.orElse(null), value);
        return toDTO(definition, repository.find(key).orElse(null));
    }

    /**
     * Stores a value without an audit row of its own, for callers that audit the action themselves
     * (pause / resume a job). Must run in the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void store(UUID adminId, String key, Object rawValue) {
        Object value = definition(key).validate(rawValue);
        repository.lockValue(key);
        repository.upsert(key, value, adminId, clock.instant());
    }

    /** Removes the override, so the configured default applies again; idempotent, audited only when one existed. */
    @Transactional
    public AdminSettingDTO reset(UUID adminId, String key) {
        AdminSettingDefinition definition = definition(key);
        Optional<Object> old = repository.lockValue(key);
        if (old.isPresent() && repository.delete(key)) {
            audit.record(adminId, AdminAction.SETTING_RESET, key, old.get(), null);
        }
        return toDTO(definition, null);
    }

    /** The effective value of an INTEGER setting (override clamped to its bounds, or the default). */
    @Transactional(readOnly = true)
    public long intValue(String key) {
        return ((Number) effective(key)).longValue();
    }

    /** The effective value of a BOOLEAN setting. */
    @Transactional(readOnly = true)
    public boolean boolValue(String key) {
        return (Boolean) effective(key);
    }

    private Object effective(String key) {
        AdminSettingDefinition definition = registry.find(key)
                .orElseThrow(() -> new IllegalArgumentException("Not a registered setting: " + key));
        return definition.effective(repository.find(key).map(StoredSetting::value).orElse(null));
    }

    private AdminSettingDefinition definition(String key) {
        return registry.find(key).orElseThrow(() -> new BadRequestException(UNKNOWN_KEY));
    }

    private static AdminSettingDTO toDTO(AdminSettingDefinition definition, StoredSetting stored) {
        return new AdminSettingDTO(definition.key(), definition.group(), definition.label(), definition.description(),
                definition.unit(), definition.type(),
                definition.effective(stored == null ? null : stored.value()), definition.defaultValue(),
                stored != null, definition.min(), definition.max(),
                stored == null ? null : stored.updatedAt(), stored == null ? null : stored.updatedBy());
    }
}

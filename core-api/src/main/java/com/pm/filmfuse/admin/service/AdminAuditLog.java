package com.pm.filmfuse.admin.service;

import com.pm.filmfuse.admin.dto.AdminAction;
import com.pm.filmfuse.admin.dto.AdminAuditEntryDTO;
import com.pm.filmfuse.admin.repository.AdminAuditRepository;
import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin audit log. {@link #record} must run inside the admin write's own transaction
 * ({@code MANDATORY}), so a write and its audit row commit or roll back together.
 */
@Service
public class AdminAuditLog {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLog.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final AdminAuditRepository repository;
    private final Clock clock;

    public AdminAuditLog(AdminAuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID adminId, AdminAction action, String target, Object oldValue, Object newValue) {
        repository.insert(UUID.randomUUID(), adminId, action, target, oldValue, newValue, clock.instant());
        log.info("Admin {} did {} on {}", adminId, action, target);
    }

    @Transactional(readOnly = true)
    public PageDTO<AdminAuditEntryDTO> page(AdminAction action, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null) {
            List<String> parts = CursorCodec.decode(cursor, 2);
            try {
                beforeAt = Instant.parse(parts.get(0));
                beforeId = UUID.fromString(parts.get(1));
            } catch (DateTimeParseException | IllegalArgumentException ex) {
                throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            }
        }
        List<AdminAuditEntryDTO> rows = repository.page(action, beforeAt, beforeId, size + 1);
        boolean more = rows.size() > size;
        List<AdminAuditEntryDTO> items = more ? rows.subList(0, size) : rows;
        String next = more ? CursorCodec.encode(List.of(items.getLast().createdAt().toString(),
                items.getLast().id().toString())) : null;
        return new PageDTO<>(List.copyOf(items), next);
    }
}

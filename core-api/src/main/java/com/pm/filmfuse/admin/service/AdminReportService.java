package com.pm.filmfuse.admin.service;

import com.pm.filmfuse.admin.dto.AdminAction;
import com.pm.filmfuse.admin.dto.AdminReportDTO;
import com.pm.filmfuse.admin.dto.ReportResolution;
import com.pm.filmfuse.admin.dto.ReportStatus;
import com.pm.filmfuse.admin.dto.ResolveReportDTO;
import com.pm.filmfuse.admin.repository.AdminReportRepository;
import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.ConflictException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The moderation queue: list reports by status, resolve or dismiss an OPEN one (audited). */
@Service
public class AdminReportService {

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;
    static final String NOT_FOUND = "Report not found.";
    static final String ALREADY_CLOSED = "This report has already been handled.";

    private final AdminReportRepository reports;
    private final AdminAuditLog audit;
    private final Clock clock;

    public AdminReportService(AdminReportRepository reports, AdminAuditLog audit, Clock clock) {
        this.reports = reports;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageDTO<AdminReportDTO> page(ReportStatus status, String cursor, Integer limit) {
        ReportStatus listed = status == null ? ReportStatus.OPEN : status;
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        Instant at = null;
        UUID id = null;
        if (cursor != null) {
            List<String> parts = CursorCodec.decode(cursor, 2);
            try {
                at = Instant.parse(parts.get(0));
                id = UUID.fromString(parts.get(1));
            } catch (DateTimeParseException | IllegalArgumentException ex) {
                throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            }
        }
        List<AdminReportDTO> rows = reports.page(listed, at, id, size + 1);
        boolean more = rows.size() > size;
        List<AdminReportDTO> items = more ? rows.subList(0, size) : rows;
        String next = more ? CursorCodec.encode(List.of(items.getLast().createdAt().toString(),
                items.getLast().id().toString())) : null;
        return new PageDTO<>(List.copyOf(items), next);
    }

    /** 404 for an unknown report, 409 when it is no longer OPEN. */
    @Transactional
    public AdminReportDTO resolve(UUID adminId, UUID reportId, ResolveReportDTO request) {
        ReportStatus current = reports.lockStatus(reportId).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        if (current != ReportStatus.OPEN) {
            throw new ConflictException(ALREADY_CLOSED);
        }
        ReportStatus closed = request.resolution() == ReportResolution.RESOLVED ? ReportStatus.RESOLVED
                : ReportStatus.DISMISSED;
        reports.close(reportId, closed, adminId, request.note(), clock.instant());
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("status", closed.name());
        newValue.put("note", request.note());
        audit.record(adminId, closed == ReportStatus.RESOLVED ? AdminAction.REPORT_RESOLVE : AdminAction.REPORT_DISMISS,
                "report:" + reportId, Map.of("status", current.name()), newValue);
        return reports.find(reportId).orElseThrow();
    }
}

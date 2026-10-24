package com.pm.filmfuse.admin.controller;

import com.pm.filmfuse.admin.dto.AdminReportDTO;
import com.pm.filmfuse.admin.dto.ReportStatus;
import com.pm.filmfuse.admin.dto.ResolveReportDTO;
import com.pm.filmfuse.admin.service.AdminReportService;
import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The reports queue. Admin-only via SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final AdminReportService reports;

    public AdminReportController(AdminReportService reports) {
        this.reports = reports;
    }

    /** {@code status} defaults to OPEN (oldest first); RESOLVED / DISMISSED are newest first. limit 1-100, default 25. */
    @GetMapping
    public PageDTO<AdminReportDTO> list(@RequestParam(required = false) ReportStatus status,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return reports.page(status, cursor, limit);
    }

    /** Closes an OPEN report; 404 unknown, 409 already handled. */
    @PostMapping("/{id}/resolve")
    public AdminReportDTO resolve(@CurrentUser Profile admin, @PathVariable UUID id,
                                  @Valid @RequestBody ResolveReportDTO request) {
        return reports.resolve(admin.getId(), id, request);
    }
}

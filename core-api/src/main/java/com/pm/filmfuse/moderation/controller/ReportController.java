package com.pm.filmfuse.moderation.controller;

import com.pm.filmfuse.moderation.dto.CreateReportDTO;
import com.pm.filmfuse.moderation.service.ReportService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reporting ratings, comments and users (contract: Phase 5 section D). */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 204, idempotent per target while the report is open; 404 unknown / not visible; 400 self-report. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@CurrentUser Profile caller, @Valid @RequestBody CreateReportDTO request) {
        reportService.report(caller.getId(), request);
    }
}

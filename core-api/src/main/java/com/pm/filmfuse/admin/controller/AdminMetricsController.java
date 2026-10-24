package com.pm.filmfuse.admin.controller;

import com.pm.filmfuse.admin.dto.CatalogMetricsDTO;
import com.pm.filmfuse.admin.dto.HealthMetricsDTO;
import com.pm.filmfuse.admin.dto.UsageMetricsDTO;
import com.pm.filmfuse.admin.service.AdminMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only metrics. Admin-only like everything under {@code /api/v1/admin} - enforced once in
 * {@code SecurityConfig} ({@link com.pm.filmfuse.admin.security.AdminAccess}), not here.
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
public class AdminMetricsController {

    private final AdminMetricsService metrics;

    public AdminMetricsController(AdminMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/catalog")
    public CatalogMetricsDTO catalog() {
        return metrics.catalog();
    }

    @GetMapping("/usage")
    public UsageMetricsDTO usage() {
        return metrics.usage();
    }

    @GetMapping("/health")
    public HealthMetricsDTO health() {
        return metrics.health();
    }
}

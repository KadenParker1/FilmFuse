package com.pm.filmfuse.admin.controller;

import com.pm.filmfuse.admin.dto.AdminAction;
import com.pm.filmfuse.admin.dto.AdminAuditEntryDTO;
import com.pm.filmfuse.admin.service.AdminAuditLog;
import com.pm.filmfuse.common.dto.PageDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin audit log, newest first. Admin-only via SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
public class AdminAuditController {

    private final AdminAuditLog audit;

    public AdminAuditController(AdminAuditLog audit) {
        this.audit = audit;
    }

    /** Optional {@code action} filter; limit 1-100, default 50. */
    @GetMapping
    public PageDTO<AdminAuditEntryDTO> list(@RequestParam(required = false) AdminAction action,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return audit.page(action, cursor, limit);
    }
}

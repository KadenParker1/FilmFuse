package com.pm.filmfuse.admin.dto;

/** What an admin did, as written to {@code app.admin_audit_log}. Mirrored as a string union in client/types/api.ts. */
public enum AdminAction {
    SETTING_UPDATE,
    SETTING_RESET,
    JOB_RUN,
    JOB_PAUSE,
    JOB_RESUME,
    REPORT_RESOLVE,
    REPORT_DISMISS
}

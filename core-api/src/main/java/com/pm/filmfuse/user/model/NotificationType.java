package com.pm.filmfuse.user.model;

/**
 * The kinds of email a user can switch off in {@code /me/settings/notifications}. Each maps to one
 * {@code app.notification_settings} column and one {@code NotificationSettingsDTO} field.
 */
public enum NotificationType {
    /** Friend request received, and friend request accepted. */
    FRIEND_REQUESTS("friend_requests"),
    GROUP_INVITES("group_invites"),
    SESSION_STARTED("session_started"),
    COMMENTS("comments"),
    WEEKLY_DIGEST("weekly_digest");

    private final String column;

    NotificationType(String column) {
        this.column = column;
    }

    /** The {@code app.notification_settings} column - a constant, safe to splice into SQL. */
    public String column() {
        return column;
    }
}

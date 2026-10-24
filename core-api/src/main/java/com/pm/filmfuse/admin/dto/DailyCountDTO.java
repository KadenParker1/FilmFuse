package com.pm.filmfuse.admin.dto;

import java.time.LocalDate;

/** A count for one UTC day ({@code date} as {@code yyyy-MM-dd}). Mirrored by hand in client/types/api.ts. */
public record DailyCountDTO(LocalDate date, long count) {
}

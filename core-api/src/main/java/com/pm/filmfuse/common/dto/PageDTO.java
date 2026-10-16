package com.pm.filmfuse.common.dto;

import java.util.List;

/**
 * One page of a cursor-paginated list. {@code nextCursor} is opaque to the client (pass it back as
 * {@code ?cursor=}) and is {@code null} on the last page. Mirrored by hand in client/types/api.ts.
 */
public record PageDTO<T>(List<T> items, String nextCursor) {
}

package com.pm.filmfuse.common.pagination;

import com.pm.filmfuse.common.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    void roundTripsPartsIncludingEmptyOnes() {
        List<String> parts = List.of("1", "0.8", "", "4f0c1a2e-0000-0000-0000-000000000000");
        String cursor = CursorCodec.encode(parts);
        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(CursorCodec.decode(cursor, 4)).isEqualTo(parts);
    }

    @Test
    void roundTripsUnicode() {
        assertThat(CursorCodec.decode(CursorCodec.encode(List.of("Zoë ✨")), 1)).containsExactly("Zoë ✨");
    }

    @Test
    void rejectsGarbageWrongArityAndOversizedCursors() {
        String twoParts = CursorCodec.encode(List.of("a", "b"));
        for (String bad : new String[] {null, "", "  ", "!!!", "a".repeat(2000)}) {
            assertThatThrownBy(() -> CursorCodec.decode(bad, 1))
                    .isInstanceOf(BadRequestException.class).hasMessage("Invalid cursor.");
        }
        assertThatThrownBy(() -> CursorCodec.decode(twoParts, 1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CursorCodec.decode(twoParts, 3)).isInstanceOf(BadRequestException.class);
        String raw = Base64.getUrlEncoder().encodeToString("plain".getBytes(StandardCharsets.UTF_8));
        assertThat(CursorCodec.decode(raw, 1)).containsExactly("plain");
    }

    @Test
    void refusesToEncodeUnencodableParts() {
        assertThatThrownBy(() -> CursorCodec.encode(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CursorCodec.encode(List.of("a\u001Fb"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CursorCodec.encode(java.util.Arrays.asList("a", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

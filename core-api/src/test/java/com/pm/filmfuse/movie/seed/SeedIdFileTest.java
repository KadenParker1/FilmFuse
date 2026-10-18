package com.pm.filmfuse.movie.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedIdFileTest {

    @Test
    void readsOneIdPerLineIgnoringCommentsBlanksAndDuplicates() {
        List<Integer> ids = SeedIdFile.parse(List.of(
                "# MovieLens ids", "862", "", "  8844  ", "862", "550 # Fight Club", "\t", "#13"));

        assertThat(ids).containsExactly(862, 8844, 550);
    }

    @Test
    void anEmptyFileHasNoIds() {
        assertThat(SeedIdFile.parse(List.of())).isEmpty();
        assertThat(SeedIdFile.parse(List.of("# nothing yet", ""))).isEmpty();
    }

    @Test
    void aMalformedLineIsAnErrorNamingTheLine() {
        assertThatThrownBy(() -> SeedIdFile.parse(List.of("1", "tt0114709")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 2").hasMessageContaining("tt0114709");
        assertThatThrownBy(() -> SeedIdFile.parse(List.of("0"))).hasMessageContaining("Line 1");
        assertThatThrownBy(() -> SeedIdFile.parse(List.of("-5"))).hasMessageContaining("Line 1");
        assertThatThrownBy(() -> SeedIdFile.parse(List.of("12.5"))).hasMessageContaining("Line 1");
        assertThatThrownBy(() -> SeedIdFile.parse(List.of("99999999999"))).hasMessageContaining("Line 1");
    }

    @Test
    void readsAFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ids.txt");
        Files.writeString(file, "# header\n603\r\n13\n");

        assertThat(SeedIdFile.read(file)).containsExactly(603, 13);
    }

    @Test
    void aMissingFileIsAClearError(@TempDir Path dir) {
        assertThatThrownBy(() -> SeedIdFile.read(dir.resolve("nope.txt")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found").hasMessageContaining("nope.txt");
    }
}

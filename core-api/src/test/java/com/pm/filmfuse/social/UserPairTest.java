package com.pm.filmfuse.social;

import com.pm.filmfuse.social.model.UserPair;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPairTest {

    @Test
    void ordersCanonicallyWhicheverWayRound() {
        UUID a = UUID.fromString("10000000-0000-0000-0000-000000000000");
        UUID b = UUID.fromString("20000000-0000-0000-0000-000000000000");
        assertThat(UserPair.of(a, b)).isEqualTo(UserPair.of(b, a));
        assertThat(UserPair.of(b, a).low()).isEqualTo(a);
        assertThat(UserPair.of(a, b).other(a)).isEqualTo(b);
        assertThat(UserPair.of(a, b).other(b)).isEqualTo(a);
        assertThat(UserPair.of(a, b).lockKey()).isEqualTo(UserPair.of(b, a).lockKey());
    }

    @Test
    void usesPostgresUnsignedOrderNotUuidCompareTo() {
        // UUID.compareTo sees the high bit as negative; Postgres compares bytes unsigned.
        UUID highBit = UUID.fromString("f0000000-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(highBit.compareTo(low)).isNegative();
        assertThat(UserPair.of(highBit, low).low()).isEqualTo(low);

        UUID lsbHigh = UUID.fromString("00000000-0000-0000-8000-000000000000");
        UUID lsbLow = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
        assertThat(UserPair.of(lsbHigh, lsbLow).low()).isEqualTo(lsbLow);
    }

    @RepeatedTest(20)
    void agreesWithStringOrderOfTheCanonicalForm() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID expectedLow = a.toString().compareTo(b.toString()) < 0 ? a : b;
        assertThat(UserPair.of(a, b).low()).isEqualTo(expectedLow);
    }

    @Test
    void rejectsSelfPairsWrongOrderAndOutsiders() {
        UUID a = UUID.fromString("10000000-0000-0000-0000-000000000000");
        UUID b = UUID.fromString("20000000-0000-0000-0000-000000000000");
        assertThatThrownBy(() -> UserPair.of(a, a)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserPair(b, a)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserPair(a, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserPair.of(a, b).other(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }
}

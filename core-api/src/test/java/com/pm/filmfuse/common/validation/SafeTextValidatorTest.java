package com.pm.filmfuse.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SafeTextValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "Ada Lovelace",
            "Zoë Ñúñez-O'Brien",
            "名前 テスト",
            "مرحبا بالعالم",              // right-to-left script on its own is fine
            "Family 👨‍👩‍👧 night", // ZWJ inside an emoji sequence
            "🏳️‍🌈",
            "Tabs? no-break space is fine",
    })
    void acceptsOrdinaryTextInBothModes(String value) {
        assertThat(SafeTextValidator.isSafe(value, false)).isTrue();
        assertThat(SafeTextValidator.isSafe(value, true)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a\u0000b", "a\u0007b", "a\tb", "a\rb", "a\u001Bb", "a\u007Fb", "a\u0080b", "a\u0085b", "a\u009Fb",
            "evil‮txt.exe", "a‪b", "a‫b", "a‬b", "a‭b",
            "a⁦b", "a⁧b", "a⁨b", "a⁩b",
            "​Ada", "Ada​", "‍Ada", "Ada‌", "⁠Ada", "﻿Ada", "Ada﻿",
    })
    void rejectsControlBidiAndEdgeZeroWidthCharactersInBothModes(String value) {
        assertThat(SafeTextValidator.isSafe(value, false)).isFalse();
        assertThat(SafeTextValidator.isSafe(value, true)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"line one\nline two", "trailing\n", "\n\nparagraphs\n\nhere"})
    void lineFeedsOnlyInMultilineText(String value) {
        assertThat(SafeTextValidator.isSafe(value, true)).isTrue();
        assertThat(SafeTextValidator.isSafe(value, false)).isFalse();
    }
}

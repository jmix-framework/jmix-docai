package com.company.docai.view.search;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plural forms of the number of the documents of a tag in the search palette.
 */
class SearchPalettePluralFormTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Test
    void russianTakesTheFormByTheLastDigitsOfTheNumber() {
        assertThat(LongStream.of(1, 21, 101)
                .mapToObj(count -> SearchPaletteView.pluralForm(count, RUSSIAN)))
                .containsOnly("one");
        assertThat(LongStream.of(2, 3, 4, 22)
                .mapToObj(count -> SearchPaletteView.pluralForm(count, RUSSIAN)))
                .containsOnly("few");
        assertThat(LongStream.of(0, 5, 11, 12, 13, 14, 25, 111)
                .mapToObj(count -> SearchPaletteView.pluralForm(count, RUSSIAN)))
                .containsOnly("many");
    }

    @Test
    void englishHasOneForOneOnly() {
        assertThat(SearchPaletteView.pluralForm(1, Locale.ENGLISH)).isEqualTo("one");
        assertThat(LongStream.of(0, 2, 5, 21)
                .mapToObj(count -> SearchPaletteView.pluralForm(count, Locale.ENGLISH)))
                .containsOnly("many");
    }
}

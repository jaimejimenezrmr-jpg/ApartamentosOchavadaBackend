package com.ochavada.backend.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlugsTest {

    @Test
    void removesAccentsAndPunctuation() {
        assertThat(Slugs.slugify("Ático Dúplex con Terraza (2 dormitorios)"))
                .isEqualTo("atico-duplex-con-terraza-2-dormitorios");
        assertThat(Slugs.slugify("  Señorío   de la Ochavada!! ")).isEqualTo("senorio-de-la-ochavada");
    }

    @Test
    void resultMatchesPublishedPattern() {
        assertThat(Slugs.slugify("Apartamento Nº 3 · Vista Plaza")).matches(Slugs.PATTERN);
    }

    @Test
    void truncatesLongNames() {
        assertThat(Slugs.slugify("a".repeat(200))).hasSize(Slugs.MAX_LENGTH);
    }
}

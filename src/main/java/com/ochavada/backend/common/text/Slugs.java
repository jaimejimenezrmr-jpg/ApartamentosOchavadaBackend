package com.ochavada.backend.common.text;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Generación de slugs para URLs amigables con SEO: "Ático Dúplex con Terraza" → "atico-duplex-con-terraza".
 */
public final class Slugs {

    public static final int MAX_LENGTH = 120;
    public static final String PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    private Slugs() {
    }

    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = withoutAccents.toLowerCase(Locale.ROOT)
                .replace("ñ", "n")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug;
    }
}

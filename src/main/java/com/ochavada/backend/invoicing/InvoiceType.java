package com.ochavada.backend.invoicing;

/** Tipos de factura de la AEAT (SII / VERI*FACTU). */
public enum InvoiceType {
    /** Factura completa (art. 6 RD 1619/2012). */
    F1,
    /** Factura simplificada (art. 7 RD 1619/2012). */
    F2,
    /** Rectificativa por error fundado en derecho y art. 80.1, 80.2 y 80.6 LIVA. */
    R1,
    /** Rectificativa art. 80.3 LIVA (concurso). */
    R2,
    /** Rectificativa art. 80.4 LIVA (créditos incobrables). */
    R3,
    /** Rectificativa (resto de casos). */
    R4,
    /** Rectificativa de facturas simplificadas. */
    R5;

    public boolean isRectifying() {
        return name().startsWith("R");
    }
}

package com.ochavada.backend.payment;

public enum PaymentStatus {
    PENDING, REQUIRES_ACTION, SUCCEEDED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == CANCELLED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}

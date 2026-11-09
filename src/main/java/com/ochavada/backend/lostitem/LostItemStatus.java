package com.ochavada.backend.lostitem;

import java.util.EnumSet;
import java.util.Set;

/** Ciclo de un objeto olvidado: guardado → huésped avisado → enviado / recogido / desechado. */
public enum LostItemStatus {
    STORED, GUEST_NOTIFIED, SHIPPED, COLLECTED, DISCARDED;

    public static final Set<LostItemStatus> OPEN = EnumSet.of(STORED, GUEST_NOTIFIED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}

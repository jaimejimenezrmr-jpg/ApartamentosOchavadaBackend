package com.ochavada.backend.ses;

/** Enumerados de las comunicaciones a SES.HOSPEDAJES. */
public final class ReportEnums {

    private ReportEnums() {
    }

    public enum CommunicationType {
        /** RH – reserva de hospedaje. */
        RESERVATION("RH"),
        /** PV – parte de viajeros (entrada). */
        TRAVELER_ENTRY("PV");

        private final String sesCode;

        CommunicationType(String sesCode) {
            this.sesCode = sesCode;
        }

        public String sesCode() {
            return sesCode;
        }
    }

    /** A = alta, B = anulación (una modificación se comunica como anulación + nueva alta). */
    public enum Operation {
        A, M, B
    }

    public enum ReportStatus {
        /** Datos incompletos: no se puede enviar hasta corregirlos. */
        DRAFT,
        /** En cola para enviarse. */
        PENDING,
        /** Enviada: el servicio ha asignado lote y se espera su procesamiento. */
        SUBMITTED,
        /** Registrada por el Ministerio (con código de comunicación). */
        ACCEPTED,
        /** El Ministerio la rechazó por errores de datos. */
        REJECTED,
        /** Error de envío o de cabecera que requiere revisión. */
        ERROR,
        /** Sustituida o anulada. */
        CANCELLED;

        public boolean isOpen() {
            return this == DRAFT || this == PENDING || this == SUBMITTED || this == REJECTED || this == ERROR;
        }
    }

    public enum PersonRole {
        /** Titular del contrato (solo en reservas). */
        TI,
        /** Viajero. */
        VI
    }
}

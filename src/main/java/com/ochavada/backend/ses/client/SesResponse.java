package com.ochavada.backend.ses.client;

import java.util.List;

/**
 * Respuesta del Servicio de Comunicación.
 *
 * @param code    {@code codigoRetorno}/{@code codigo}: 0 = correcto (tabla de códigos del apartado 5)
 * @param batchId lote asignado (altas y anulaciones)
 * @param batches resultado de cada lote consultado (operación de consulta)
 * @param raw     cuerpo SOAP recibido, para auditoría
 */
public record SesResponse(int code, String description, String batchId, List<BatchResult> batches, String raw) {

    public boolean isOk() {
        return code == 0;
    }

    /**
     * @param stateCode 1 tramitado sin errores, 2 errores de cabecera/formato, 3 error inesperado,
     *                  4 en proceso, 5 pendiente, 6 tramitado con errores en algunas comunicaciones
     */
    public record BatchResult(String batchId, int stateCode, String stateDescription,
                              List<CommunicationResult> communications) {

        public boolean isFinished() {
            return stateCode != 4 && stateCode != 5;
        }
    }

    /** Resultado de una comunicación del lote: código asignado o error de validación. */
    public record CommunicationResult(int order, String communicationCode, String errorType, String error,
                                      boolean cancelled) {
    }
}

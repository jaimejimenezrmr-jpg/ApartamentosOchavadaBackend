package com.ochavada.backend.access.ttlock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Respuestas de la TTLock Open API v3 (solo los campos utilizados). */
public final class TtlockDtos {

    private TtlockDtos() {
    }

    /** Contrato común: {@code errcode} 0 (o ausente) = correcto. */
    public interface WithError {
        Integer errcode();

        String errmsg();

        default boolean isOk() {
            return errcode() == null || errcode() == 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("scope") String scope,
            @JsonProperty("uid") Long uid,
            Integer errcode,
            String errmsg,
            String description) implements WithError {

        @Override
        public boolean isOk() {
            return accessToken != null && !accessToken.isBlank() && (errcode == null || errcode == 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OperationResult(Integer errcode, String errmsg, String description) implements WithError {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LockSummary(
            Long lockId,
            String lockName,
            String lockAlias,
            String lockMac,
            Integer electricQuantity,
            Integer hasGateway) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LockPage(
            List<LockSummary> list,
            Integer pageNo,
            Integer pageSize,
            Integer pages,
            Integer total,
            Integer errcode,
            String errmsg) implements WithError {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LockDetail(
            Long lockId,
            String lockAlias,
            String lockMac,
            Integer electricQuantity,
            Integer hasGateway,
            Integer errcode,
            String errmsg) implements WithError {
    }
}

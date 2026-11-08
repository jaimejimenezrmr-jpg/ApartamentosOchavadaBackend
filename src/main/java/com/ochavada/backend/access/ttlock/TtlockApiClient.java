package com.ochavada.backend.access.ttlock;

import com.ochavada.backend.access.ttlock.TtlockDtos.LockDetail;
import com.ochavada.backend.access.ttlock.TtlockDtos.LockPage;
import com.ochavada.backend.access.ttlock.TtlockDtos.OperationResult;
import com.ochavada.backend.access.ttlock.TtlockDtos.TokenResponse;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Cliente declarativo de la TTLock Open API v3. Las operaciones de escritura y la autenticación se envían
 * como {@code application/x-www-form-urlencoded} en el cuerpo (los secretos no viajan en la URL); las
 * consultas, que la API solo admite por GET, llevan el accessToken como parámetro.
 * <p>
 * La API responde HTTP 200 incluso ante errores de negocio: el resultado real viene en {@code errcode}
 * (0 = correcto), que interpreta {@link TtlockGateway}.
 */
@FeignClient(name = "ttlock", url = "${app.ttlock.base-url}", configuration = TtlockFeignConfig.class)
public interface TtlockApiClient {

    /** Obtiene ({@code grant_type=password}) o renueva ({@code grant_type=refresh_token}) el token OAuth. */
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    TokenResponse token(@RequestBody Map<String, ?> form);

    /** Apertura remota (requiere gateway Wi-Fi conectado a la cerradura). */
    @PostMapping(value = "/v3/lock/unlock", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    OperationResult unlock(@RequestBody Map<String, ?> form);

    /** Consulta paginada de las cerraduras de la cuenta (la API solo la admite por GET). */
    @GetMapping("/v3/lock/list")
    LockPage listLocks(@RequestParam("clientId") String clientId, @RequestParam("accessToken") String accessToken,
                       @RequestParam("pageNo") int pageNo, @RequestParam("pageSize") int pageSize,
                       @RequestParam("date") long date);

    @GetMapping("/v3/lock/detail")
    LockDetail lockDetail(@RequestParam("clientId") String clientId, @RequestParam("accessToken") String accessToken,
                          @RequestParam("lockId") long lockId, @RequestParam("date") long date);
}

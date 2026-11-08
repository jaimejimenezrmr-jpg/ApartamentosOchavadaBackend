package com.ochavada.backend.common.error;

import com.ochavada.backend.common.web.TooManyRequestsException;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traducción centralizada de excepciones a {@code application/problem+json} (RFC 7807).
 * <p>
 * Hereda de {@link ResponseEntityExceptionHandler} para cubrir todas las excepciones estándar de
 * Spring MVC (400, 404, 405, 406, 415...) y las normaliza con {@link ProblemDetailFactory}.
 * También recibe las excepciones de seguridad lanzadas en la cadena de filtros, a través del
 * entry point y del access denied handler.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";
    private static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";

    private final ProblemDetailFactory problems;

    public GlobalExceptionHandler(ProblemDetailFactory problems) {
        this.problems = problems;
    }

    // ---------------------------------------------------------------------------------------------
    // Negocio
    // ---------------------------------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, WebRequest request) {
        ProblemDetail problem = problems.create(ex.getStatus(), ex.getCode(), ex.getTitle(), ex.getMessage(),
                ex.getProperties(), request);
        return problemResponse(problem);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex, WebRequest request) {
        ProblemDetail problem = problems.create(ex.getStatus(), ex.getCode(), ex.getTitle(), ex.getMessage(),
                ex.getProperties(), request);
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    // ---------------------------------------------------------------------------------------------
    // Seguridad
    // ---------------------------------------------------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, WebRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.UNAUTHORIZED, "auth.unauthenticated",
                "Autenticación requerida", "Se requiere un token de acceso válido", Map.of(), request);
        return problemResponse(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.FORBIDDEN, "auth.forbidden",
                "Acceso denegado", "No tiene permisos para realizar esta operación", Map.of(), request);
        return problemResponse(problem);
    }

    // ---------------------------------------------------------------------------------------------
    // Validación
    // ---------------------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            violations.add(new FieldViolation(error.getField(), error.getDefaultMessage()));
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            violations.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage()));
        }
        return validationProblem(violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> violations.add(
                    new FieldViolation(parameter != null ? parameter : "parameter", error.getDefaultMessage())));
        });
        return validationProblem(violations, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationProblem(violations, request);
    }

    // ---------------------------------------------------------------------------------------------
    // Persistencia
    // ---------------------------------------------------------------------------------------------

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        String sqlState = findSqlState(ex);
        log.warn("Violación de integridad de datos (SQLState {}): {}", sqlState, ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = switch (sqlState == null ? "" : sqlState) {
            case SQLSTATE_UNIQUE_VIOLATION -> problems.create(HttpStatus.CONFLICT, "data.duplicate",
                    "Registro duplicado", "Ya existe un registro con esos datos", Map.of(), request);
            case SQLSTATE_EXCLUSION_VIOLATION -> problems.create(HttpStatus.CONFLICT, "data.overlap",
                    "Fechas solapadas", "El rango de fechas se solapa con otro existente para el mismo recurso",
                    Map.of(), request);
            case SQLSTATE_FOREIGN_KEY_VIOLATION -> problems.create(HttpStatus.CONFLICT, "data.reference",
                    "Referencia no válida", "El registro está referenciado o referencia a otro inexistente",
                    Map.of(), request);
            case SQLSTATE_CHECK_VIOLATION -> problems.create(HttpStatus.UNPROCESSABLE_CONTENT, "data.constraint",
                    "Datos no válidos", "Los datos no cumplen las restricciones del modelo", Map.of(), request);
            default -> problems.create(HttpStatus.CONFLICT, "data.integrity",
                    "Conflicto de integridad", "La operación viola la integridad de los datos", Map.of(), request);
        };
        return problemResponse(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              WebRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.CONFLICT, "data.concurrent_modification",
                "Modificación concurrente",
                "El registro fue modificado por otro usuario. Recargue los datos e inténtelo de nuevo",
                Map.of(), request);
        return problemResponse(problem);
    }

    // ---------------------------------------------------------------------------------------------
    // Último recurso
    // ---------------------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        String errorId = UUID.randomUUID().toString();
        log.error("Error no controlado [errorId={}]", errorId, ex);
        ProblemDetail problem = problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "internal.error",
                "Error interno", "Se ha producido un error inesperado. Referencia: " + errorId,
                Map.of("errorId", errorId), request);
        return problemResponse(problem);
    }

    /**
     * Punto común por el que pasan todas las respuestas de {@link ResponseEntityExceptionHandler}:
     * se normalizan con los mismos miembros que las excepciones propias.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problems.enrich(problem, request);
            HttpHeaders problemHeaders = new HttpHeaders();
            problemHeaders.putAll(headers);
            problemHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return new ResponseEntity<>(problem, problemHeaders, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(List<FieldViolation> violations, WebRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "validation.failed",
                "Datos de entrada no válidos", "La petición contiene campos no válidos",
                Map.of("errors", violations), request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ResponseEntity<ProblemDetail> problemResponse(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String findSqlState(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    /** Error de validación de un campo concreto. */
    public record FieldViolation(String field, String message) {
    }
}

package com.kardo.exception;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Manejo centralizado de excepciones con los codigos acordados en la arquitectura. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Carga rechazada por exceder el tamano maximo: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "El archivo excede el tamano maximo permitido de 10MB.");
    }

    @ExceptionHandler(CorruptSpreadsheetException.class)
    public ResponseEntity<ApiError> handleCorruptSpreadsheet(CorruptSpreadsheetException ex) {
        log.warn("Ingesta abortada tras {} filas corruptas", ex.getCorruptRows());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * Intento de operar sobre otra tienda, o sin el rol necesario.
     *
     * <p>El detalle del intento queda en el log, no en la respuesta: decirle al
     * cliente que tienda existe o que rol le falta le da material para seguir
     * probando.
     */
    @ExceptionHandler({ForbiddenStoreException.class, AccessDeniedException.class})
    public ResponseEntity<ApiError> handleForbidden(RuntimeException ex) {
        log.warn("Acceso denegado: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "No tienes permiso para realizar esta operacion.");
    }

    @ExceptionHandler({UnreadableImageException.class, InvalidReceiptLineException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Toda la cadena de modelos de vision esta saturada: reintentar mas tarde. */
    @ExceptionHandler(GeminiUnavailableException.class)
    public ResponseEntity<ApiError> handleGeminiUnavailable(GeminiUnavailableException ex) {
        log.warn("Vision IA no disponible: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /** La IA respondio algo que no es la guia pactada: fallo aguas arriba. */
    @ExceptionHandler(GeminiExtractionException.class)
    public ResponseEntity<ApiError> handleGeminiExtraction(GeminiExtractionException ex) {
        log.warn("Extraccion documental fallida: {}", ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY,
                "No se pudo interpretar el documento. Reintenta con una foto mas nitida.");
    }

    /** Guia ya registrada: uq_grn_store impide duplicar la misma guia en la tienda. */
    @ExceptionHandler(UploadNotFoundException.class)
    public ResponseEntity<ApiError> handleUploadNotFound(UploadNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Lleva el detalle de que lotes bloquean la reversion, no solo el rechazo. */
    @ExceptionHandler(UploadNotRevertibleException.class)
    public ResponseEntity<ApiError> handleNotRevertible(UploadNotRevertibleException ex) {
        log.warn("Reversion rechazada: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(), ex.blockers()));
    }

    @ExceptionHandler(DuplicateUploadException.class)
    public ResponseEntity<ApiError> handleDuplicateUpload(DuplicateUploadException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("Violacion de unicidad: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT,
                "El documento ya fue registrado para esta tienda.");
    }

    @ExceptionHandler(InvalidQuantityException.class)
    public ResponseEntity<ApiError> handleInvalidQuantity(InvalidQuantityException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({ProductNotFoundException.class, BatchNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFoundEntities(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SelfApprovalException.class)
    public ResponseEntity<ApiError> handleSelfApproval(SelfApprovalException ex) {
        log.warn("Intento de autoaprobacion: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(WasteAlreadyResolvedException.class)
    public ResponseEntity<ApiError> handleWasteResolved(WasteAlreadyResolvedException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(StoreNotFoundException.class)
    public ResponseEntity<ApiError> handleStoreNotFound(StoreNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Payload de {@code /bulk-fix} con filas que no pasan Bean Validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail.isEmpty() ? "Payload invalido." : detail);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message));
    }
}

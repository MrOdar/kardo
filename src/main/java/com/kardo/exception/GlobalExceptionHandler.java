package com.kardo.exception;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
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

    /**
     * Archivo demasiado largo: 413 y no 422.
     *
     * <p>422 significa "esto esta mal"; aqui el archivo esta bien y lo que no
     * cabe es el trabajo. La diferencia no es cosmetica: con un 413 el cliente
     * sabe que la salida es dividir el archivo, no corregirlo.
     */
    @ExceptionHandler(SpreadsheetTooLargeException.class)
    public ResponseEntity<ApiError> handleSpreadsheetTooLarge(SpreadsheetTooLargeException ex) {
        log.warn("Carga rechazada por exceder las {} filas admitidas", ex.maxRows());
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
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

    /**
     * Cupo diario de vision agotado.
     *
     * <p>429 y no 503: el servicio esta sano, es este cliente el que ya gasto lo
     * suyo. La diferencia importa porque un 503 invita a reintentar y aqui
     * reintentar no sirve hasta mañana.
     */
    /**
     * Demasiados intentos en los endpoints publicos.
     *
     * <p>Lleva {@code Retry-After} porque es la unica forma de que un cliente
     * -o el propio SPA- sepa cuanto esperar sin ponerse a sondear, que es
     * exactamente lo que el freno pretende evitar.
     */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ApiError> handleTooManyAttempts(TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(ApiError.of(HttpStatus.TOO_MANY_REQUESTS.value(),
                        HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(), ex.getMessage()));
    }

    @ExceptionHandler(VisionQuotaExceededException.class)
    public ResponseEntity<ApiError> handleVisionQuota(VisionQuotaExceededException ex) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
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

    /**
     * El RUC o el correo ya estaban cogidos.
     *
     * <p>Aqui si se devuelve el mensaje tal cual, al reves que en el login: en
     * un alta, saber que ese correo ya tiene cuenta es justo lo que necesita
     * quien la esta creando, y no revela nada que no acabe de teclear.
     */
    @ExceptionHandler(NotReturnableException.class)
    public ResponseEntity<ApiError> handleNotReturnable(NotReturnableException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(ReturnAlreadyResolvedException.class)
    public ResponseEntity<ApiError> handleReturnResolved(ReturnAlreadyResolvedException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<ApiError> handleDuplicateAccount(DuplicateAccountException ex) {
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

    /**
     * Red de seguridad: cualquier excepcion que nadie de arriba reclame.
     *
     * <p><b>Que pasaba sin esto.</b> Un {@code NullPointerException} o el
     * {@code DataAccessException} de un ERRCODE que ningun traductor contempla
     * salian por el manejador por defecto de Spring Boot, que devuelve un
     * cuerpo con {@code exception}, {@code message} y, segun la configuracion,
     * el {@code trace}. Eso le enseña al cliente el nombre de la clase que
     * fallo, la version del driver y a veces el fragmento de SQL con los
     * nombres de las tablas: un mapa gratis del interior para quien esta
     * buscando por donde entrar.
     *
     * <p><b>Que hace.</b> El detalle va al log del servidor, que es de quien
     * opera, y al cliente le llega un 500 con una frase. Va declarado al final
     * del archivo y sobre {@code Exception}, el tipo mas general: Spring elige
     * siempre el manejador mas especifico, de modo que no tapa ninguno de los
     * de arriba.
     *
     * <p>Las excepciones de Spring Security quedan fuera a proposito: las
     * resuelve la cadena de filtros antes de llegar aqui, y atraparlas
     * convertiria un 401 en un 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Spring tiene sus propios errores de protocolo -metodo no permitido,
        // ruta inexistente, parametro que falta- y cada uno ya sabe su codigo.
        // Si el catch-all se los comiera, un 405 se devolveria como 500 y el
        // cliente no sabria que lo que esta mal es su peticion.
        if (ex instanceof ErrorResponse known) {
            return ResponseEntity.status(known.getStatusCode())
                    .body(ApiError.of(known.getStatusCode().value(),
                            HttpStatus.valueOf(known.getStatusCode().value()).getReasonPhrase(),
                            known.getBody().getDetail()));
        }

        // JSON malformado. No implementa ErrorResponse, asi que entra aqui, y
        // su mensaje trae el fragmento del cuerpo que no se pudo parsear: va al
        // log y no a la respuesta.
        if (ex instanceof HttpMessageNotReadableException) {
            log.warn("Cuerpo de peticion ilegible: {}", ex.getMessage());
            return build(HttpStatus.BAD_REQUEST, "El cuerpo de la peticion no es un JSON valido.");
        }

        log.error("Fallo no contemplado", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrio un error inesperado. Si vuelve a pasar, avisa a soporte.");
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message));
    }
}

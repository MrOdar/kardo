package com.kardo.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kardo.diagnostics.RequestDiagnostics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import com.kardo.config.GeminiProperties;
import com.kardo.dto.ExtractedReceiptDto;
import com.kardo.exception.GeminiExtractionException;
import com.kardo.exception.GeminiUnavailableException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Cliente de extraccion documental con Gemini.
 *
 * <p>El tier gratuito de Google AI Studio responde 429 cuando se agota la cuota
 * por minuto y 503 cuando el modelo esta sobrecargado. Ambos son transitorios y
 * especificos del modelo, asi que el cliente recorre una cadena de modelos
 * (primario y respaldos) en vez de fallar: la recepcion de mercaderia no puede
 * depender de la carga momentanea de un modelo concreto.
 */
@Component
public class GeminiVisionClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionClient.class);

    /**
     * Se exige JSON estricto y se prohibe inventar datos: en una guia impresa es
     * mejor un campo nulo que un numero alucinado que descuadre el inventario.
     */
    static final String EXTRACTION_PROMPT = """
            Eres un extractor de datos de guias de remision y facturas peruanas.
            Devuelve EXCLUSIVAMENTE un objeto JSON valido, sin texto adicional y sin bloques de codigo.

            Esquema exacto:
            {
              "supplierTaxId": "RUC del proveedor (11 digitos) o null",
              "supplierName": "razon social del proveedor o null",
              "documentNumber": "numero de la guia o factura o null",
              "issueDate": "fecha de emision en formato yyyy-MM-dd o null",
              "items": [
                {
                  "descriptionRaw": "descripcion del producto tal como aparece impresa",
                  "invoicedQty": numero,
                  "unitCost": numero,
                  "batchCode": "lote si figura, en caso contrario null",
                  "expirationDate": "vencimiento en formato yyyy-MM-dd si figura, en caso contrario null"
                }
              ]
            }

            Reglas:
            - No inventes valores: si un dato no se lee con certeza, usa null.
            - Transcribe descriptionRaw literalmente, sin corregir ni expandir abreviaturas.
            - Usa punto como separador decimal y no incluyas simbolos de moneda.
            - Incluye una entrada en items por cada linea de producto del documento.
            """;

    /** Nombre del timer que lee el panel de diagnostico. */
    public static final String VISION_TIMER = "kardo.vision.call";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiVisionClient(RestClient.Builder restClientBuilder,
                              GeminiProperties properties,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .build();
    }

    /**
     * @param base64Jpeg imagen ya reescalada y comprimida
     * @return la guia extraida y el modelo que efectivamente respondio
     * @throws GeminiUnavailableException si toda la cadena de modelos sigue
     *                                   saturada tras agotar los intentos
     * @throws GeminiExtractionException  si la respuesta no es el JSON pactado
     */
    public GeminiExtractionResult extract(String base64Jpeg) {
        List<String> models = properties.modelChain();
        int attemptsLeft = Math.max(1, properties.maxAttempts());
        String lastFailure = null;

        for (String model : models) {
            if (attemptsLeft <= 0) {
                break;
            }
            attemptsLeft--;

            ResponseEntity<String> response;
            try {
                response = call(model, base64Jpeg);
            } catch (ResourceAccessException timeout) {
                // Timeout de lectura: se prueba el siguiente modelo de la cadena.
                lastFailure = "timeout con " + model + ": " + timeout.getMessage();
                log.warn("Gemini {} no respondio dentro del timeout", model);
                continue;
            }

            HttpStatusCode status = response.getStatusCode();
            if (status.is2xxSuccessful()) {
                log.info("Guia extraida con el modelo {}", model);
                return new GeminiExtractionResult(parseReceipt(response.getBody()), model);
            }

            if (isRetryableWithAnotherModel(status)) {
                lastFailure = "HTTP " + status.value() + " con " + model;
                log.warn("Gemini {} devolvio {} (cuota, sobrecarga o modelo retirado);"
                        + " se intenta un respaldo", model, status.value());
                continue;
            }

            // 400, 401, 403: configuracion o payload incorrectos. Reintentar con
            // otro modelo no lo arregla y solo gasta cuota.
            throw new GeminiExtractionException(
                    "Gemini rechazo la peticion con HTTP " + status.value() + ": " + response.getBody());
        }

        throw new GeminiUnavailableException(
                "El servicio de vision no esta disponible (" + lastFailure + ").");
    }

    /**
     * Fallos que otro modelo de la cadena si puede resolver.
     *
     * <p>429 (cuota del minuto agotada) y 5xx (sobrecarga) son transitorios y
     * propios del modelo. El 404 no es transitorio pero tambien es especifico:
     * Google retira modelos antiguos y responde <em>"This model is no longer
     * available"</em>. Tratarlo como error definitivo dejaria la recepcion de
     * mercaderia caida hasta un redespliegue, cuando el respaldo configurado
     * habria funcionado.
     */
    private static boolean isRetryableWithAnotherModel(HttpStatusCode status) {
        return status.value() == 429 || status.value() == 404 || status.is5xxServerError();
    }

    /**
     * Cronometra cada intento por separado.
     *
     * <p>Por modelo y por resultado, porque un p95 global mezclaria el modelo
     * primario con sus respaldos y escondería justo lo que interesa: si la
     * lentitud viene de que el primario esta saturado y se esta cayendo al
     * siguiente de la cadena en cada escaneo.
     */
    private ResponseEntity<String> call(String model, String base64Jpeg) {
        long startedAt = System.nanoTime();
        String outcome = "error";
        try {
            ResponseEntity<String> response = doCall(model, base64Jpeg);
            outcome = response.getStatusCode().is2xxSuccessful()
                    ? "ok"
                    : String.valueOf(response.getStatusCode().value());
            return response;
        } finally {
            long elapsed = System.nanoTime() - startedAt;
            Timer.builder(VISION_TIMER)
                    .description("Tiempo de cada llamada a la vision multimodal")
                    .tag("model", model)
                    .tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95)
                    .register(meterRegistry)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            RequestDiagnostics.recordVision(elapsed);
        }
    }

    private ResponseEntity<String> doCall(String model, String base64Jpeg) {
        return restClient.post()
                .uri("/models/{model}:generateContent", model)
                .contentType(MediaType.APPLICATION_JSON)
                // La clave viaja en cabecera, nunca en la URL, para que no quede
                // en logs de acceso ni en historiales de proxy.
                .header("x-goog-api-key", properties.apiKey())
                .body(requestBody(base64Jpeg))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    // Se desactiva el manejo por defecto: los 429/503 se deciden aqui.
                })
                .toEntity(String.class);
    }

    private Map<String, Object> requestBody(String base64Jpeg) {
        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("mimeType", ImageProcessingService.OUTPUT_MIME_TYPE);
        inlineData.put("data", base64Jpeg);

        Map<String, Object> contents = Map.of("parts", List.of(
                Map.of("text", EXTRACTION_PROMPT),
                Map.of("inlineData", inlineData)));

        // responseMimeType fuerza JSON y temperature 0 evita variaciones entre
        // llamadas sobre el mismo documento.
        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", MediaType.APPLICATION_JSON_VALUE,
                "temperature", 0);

        return Map.of(
                "contents", List.of(contents),
                "generationConfig", generationConfig);
    }

    private ExtractedReceiptDto parseReceipt(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new GeminiExtractionException("Gemini devolvio una respuesta vacia.");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new GeminiExtractionException(
                        "Gemini no devolvio candidatos (posible bloqueo por filtros de seguridad).");
            }

            StringBuilder payload = new StringBuilder();
            for (JsonNode part : candidates.get(0).path("content").path("parts")) {
                payload.append(part.path("text").asText(""));
            }

            String json = stripCodeFences(payload.toString().trim());
            if (json.isEmpty()) {
                throw new GeminiExtractionException("Gemini no devolvio contenido de texto.");
            }
            return objectMapper.readValue(json, ExtractedReceiptDto.class);

        } catch (GeminiExtractionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GeminiExtractionException(
                    "La respuesta de Gemini no respeta el JSON pactado: " + ex.getMessage(), ex);
        }
    }

    /**
     * Aunque se pide JSON puro, los modelos a veces envuelven la respuesta en
     * un bloque ```json. Quitarlo es mas barato que fallar la recepcion.
     */
    private static String stripCodeFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineBreak = trimmed.indexOf('\n');
        String withoutOpening = firstLineBreak < 0 ? "" : trimmed.substring(firstLineBreak + 1);
        int closing = withoutOpening.lastIndexOf("```");
        return (closing < 0 ? withoutOpening : withoutOpening.substring(0, closing)).trim();
    }

    /** Guia extraida junto al modelo que la produjo, para trazabilidad en la UI. */
    public record GeminiExtractionResult(ExtractedReceiptDto receipt, String modelUsed) {
    }
}

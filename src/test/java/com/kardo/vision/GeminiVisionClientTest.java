package com.kardo.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kardo.config.GeminiProperties;
import com.kardo.dto.ExtractedReceiptDto;
import com.kardo.exception.GeminiExtractionException;
import com.kardo.exception.GeminiUnavailableException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiVisionClientTest {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    /** Respuesta de la API con el JSON de la guia dentro del texto del candidato. */
    private static final String RESPUESTA_OK = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "{\\"supplierTaxId\\":\\"20123456789\\",\\"supplierName\\":\\"Distribuidora Lima S.A.C.\\",\\"documentNumber\\":\\"T001-4567\\",\\"issueDate\\":\\"2026-10-15\\",\\"items\\":[{\\"descriptionRaw\\":\\"CERV PILSEN 650\\",\\"invoicedQty\\":24,\\"unitCost\\":4.5,\\"batchCode\\":\\"L-A\\",\\"expirationDate\\":\\"2027-05-01\\"}]}"
                  }]
                }
              }]
            }
            """;

    @Test
    @DisplayName("Extrae la guia y manda la clave en cabecera, no en la URL")
    void extraeGuia() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(BASE_URL + "/models/gemini-2.0-flash:generateContent"))
                .andExpect(header("x-goog-api-key", "clave-de-prueba"))
                .andExpect(jsonPath("$.contents[0].parts[1].inlineData.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.contents[0].parts[1].inlineData.data").value("BASE64"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andRespond(withSuccess(RESPUESTA_OK, MediaType.APPLICATION_JSON));

        var result = client(builder).extract("BASE64");

        server.verify();
        assertThat(result.modelUsed()).isEqualTo("gemini-2.0-flash");

        ExtractedReceiptDto receipt = result.receipt();
        assertThat(receipt.supplierTaxId()).isEqualTo("20123456789");
        assertThat(receipt.documentNumber()).isEqualTo("T001-4567");
        assertThat(receipt.issueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(receipt.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionRaw()).isEqualTo("CERV PILSEN 650");
            assertThat(item.invoicedQty()).isEqualByComparingTo("24");
            assertThat(item.unitCost()).isEqualByComparingTo("4.5");
            assertThat(item.batchCode()).isEqualTo("L-A");
            assertThat(item.expirationDate()).isEqualTo(LocalDate.of(2027, 5, 1));
        });
    }

    @Test
    @DisplayName("Ante 429 del modelo primario conmuta al respaldo")
    void fallbackAnte429() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(BASE_URL + "/models/gemini-2.0-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":\"quota\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/models/gemini-2.0-flash-lite:generateContent"))
                .andRespond(withSuccess(RESPUESTA_OK, MediaType.APPLICATION_JSON));

        var result = client(builder).extract("BASE64");

        server.verify();
        assertThat(result.modelUsed()).isEqualTo("gemini-2.0-flash-lite");
    }

    @Test
    @DisplayName("Un modelo retirado (404) conmuta al respaldo en vez de fallar")
    void fallbackAnteModeloRetirado() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // Google retira modelos antiguos: "This model is no longer available".
        server.expect(requestTo(BASE_URL + "/models/gemini-2.0-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"This model is no longer available\",\"status\":\"NOT_FOUND\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/models/gemini-2.0-flash-lite:generateContent"))
                .andRespond(withSuccess(RESPUESTA_OK, MediaType.APPLICATION_JSON));

        var result = client(builder).extract("BASE64");

        server.verify();
        assertThat(result.modelUsed()).isEqualTo("gemini-2.0-flash-lite");
    }

    @Test
    @DisplayName("Si toda la cadena esta saturada responde servicio no disponible")
    void cadenaCompletaSaturada() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

        server.expect(ExpectedCount.times(3), requestTo(org.hamcrest.Matchers.containsString("generateContent")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("overloaded"));

        assertThatThrownBy(() -> client(builder).extract("BASE64"))
                .isInstanceOf(GeminiUnavailableException.class)
                .hasMessageContaining("503");

        server.verify();
    }

    @Test
    @DisplayName("Un 400 no se reintenta: el payload o la clave estan mal")
    void errorDeClienteNoReintenta() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.containsString("generateContent")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("API key not valid"));

        assertThatThrownBy(() -> client(builder).extract("BASE64"))
                .isInstanceOf(GeminiExtractionException.class)
                .hasMessageContaining("400");

        server.verify();
    }

    @Test
    @DisplayName("Tolera que el modelo envuelva el JSON en un bloque de codigo")
    void toleraBloqueDeCodigo() {
        String conFences = """
                {"candidates":[{"content":{"parts":[{"text":"```json\\n{\\"supplierTaxId\\":\\"20999\\",\\"items\\":[]}\\n```"}]}}]}
                """;

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("generateContent")))
                .andRespond(withSuccess(conFences, MediaType.APPLICATION_JSON));

        var result = client(builder).extract("BASE64");

        assertThat(result.receipt().supplierTaxId()).isEqualTo("20999");
        assertThat(result.receipt().items()).isEmpty();
    }

    @Test
    @DisplayName("Una respuesta sin candidatos se reporta como extraccion fallida")
    void respuestaSinCandidatos() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("generateContent")))
                .andRespond(withSuccess("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder).extract("BASE64"))
                .isInstanceOf(GeminiExtractionException.class)
                .hasMessageContaining("candidatos");
    }

    private static GeminiVisionClient client(RestClient.Builder builder) {
        GeminiProperties properties = new GeminiProperties(
                "clave-de-prueba",
                BASE_URL,
                "gemini-2.0-flash",
                List.of("gemini-2.0-flash-lite", "gemini-1.5-flash"),
                3,
                Duration.ofSeconds(20),
                Duration.ofSeconds(20));
        return new GeminiVisionClient(builder, properties, new ObjectMapper().findAndRegisterModules(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}

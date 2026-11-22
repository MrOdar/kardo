package com.kardo.it;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El contrato OpenAPI con el contexto completo.
 *
 * <p>Con springdoc 2.x sobre Boot 4 la ruta existia pero respondia 500: el
 * slice de {@code @WebMvcTest} no carga springdoc y no lo podia ver.
 */
@AutoConfigureMockMvc
class ApiContractIT extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/v3/api-docs devuelve el contrato OpenAPI")
    void sirveElContrato() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"openapi\"")))
                .andExpect(content().string(containsString("/api/v1/auth/login")));
    }
}

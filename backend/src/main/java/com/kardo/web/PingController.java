package com.kardo.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de Keep-Alive (Capa 1 de la estrategia hibrida 24/7).
 *
 * <p>UptimeRobot lo invoca cada 5 minutos para que la JVM del contenedor de
 * Render no se duerma. Responde desde memoria: cero consultas a BD y cero
 * conexiones tomadas del pool HikariCP, que esta acotado a 5.
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    private static final ResponseEntity<String> PONG = ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body("pong");

    @GetMapping(value = "/ping", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ping() {
        return PONG;
    }
}

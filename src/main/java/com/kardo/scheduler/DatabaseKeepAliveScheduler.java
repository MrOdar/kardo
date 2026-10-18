package com.kardo.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heartbeat anti-hibernacion de Supabase (Capa 2 de la estrategia hibrida 24/7).
 *
 * <p>El {@code /ping} en memoria mantiene viva la JVM pero nunca toca la base de
 * datos, de modo que Supabase pausaria el proyecto tras 7 dias sin SQL. Este
 * componente ejecuta un unico {@code SELECT 1} al mediodia: una consulta al dia,
 * sin consumir cuota de computo apreciable.
 *
 * <p>Se desactiva con {@code kardo.keepalive.enabled=false} (local y tests).
 */
@Component
@ConditionalOnProperty(name = "kardo.keepalive.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseKeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(DatabaseKeepAliveScheduler.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseKeepAliveScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${kardo.keepalive.cron:0 0 12 * * ?}", zone = "${kardo.keepalive.zone:America/Lima}")
    public void keepDatabaseAwake() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("Heartbeat de BD ejecutado correctamente (SELECT 1 -> {})", result);
        } catch (RuntimeException ex) {
            // Un heartbeat fallido no debe tumbar el scheduler: se reintenta al dia siguiente.
            log.error("Heartbeat de BD fallido: {}", ex.getMessage(), ex);
        }
    }
}

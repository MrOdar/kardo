package com.kardo.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borrado de la IP de origen pasada su ventana de retencion.
 *
 * <p>La IP se guarda para poder rastrear un acceso indebido, no para perfilar al
 * operario. Es dato personal bajo la Ley 29733, y guardarlo indefinidamente
 * convierte una medida de seguridad en un archivo de vigilancia sin proposito.
 * Pasada la ventana, el asiento de auditoria conserva lo que importa —quien,
 * que y cuando— y pierde solo el dato que ya no sirve.
 *
 * <p>Se borra en vez de anonimizar porque una IP truncada sigue siendo
 * identificativa en una tienda con pocos operarios.
 */
@Component
@ConditionalOnProperty(name = "kardo.audit.ip-retention-days", matchIfMissing = true)
public class ClientIpRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClientIpRetentionScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;

    public ClientIpRetentionScheduler(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value("${kardo.audit.ip-retention-days:90}")
            int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
    }

    /** De madrugada, cuando la tienda no opera y el pool esta libre. */
    @Scheduled(cron = "${kardo.audit.purge-cron:0 30 3 * * ?}", zone = "${kardo.keepalive.zone:America/Lima}")
    @Transactional
    public void purgeOldClientIps() {
        try {
            int uploads = jdbcTemplate.update("""
                    UPDATE inventory_uploads
                    SET client_ip = NULL
                    WHERE client_ip IS NOT NULL
                      AND created_at < CURRENT_TIMESTAMP - make_interval(days => ?)
                    """, retentionDays);

            int receipts = jdbcTemplate.update("""
                    UPDATE goods_receipt_headers
                    SET client_ip = NULL
                    WHERE client_ip IS NOT NULL
                      AND created_at < CURRENT_TIMESTAMP - make_interval(days => ?)
                    """, retentionDays);

            if (uploads + receipts > 0) {
                log.info("Retencion de IP ({} dias): depuradas {} cargas y {} recepciones",
                        retentionDays, uploads, receipts);
            }
        } catch (RuntimeException ex) {
            // Un fallo aqui no debe tumbar el scheduler: se reintenta mañana.
            log.error("No se pudo aplicar la retencion de IP: {}", ex.getMessage(), ex);
        }
    }
}

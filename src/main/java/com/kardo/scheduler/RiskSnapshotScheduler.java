package com.kardo.scheduler;

import com.kardo.inventory.RiskHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Foto diaria del valor en riesgo.
 *
 * <p>Corre a las 23:50 de Lima, no a medianoche: a esa hora el dia ya esta
 * cerrado -las ventas y las mermas del dia estan dentro- y la fecha sigue
 * siendo la de hoy, de modo que cada punto de la serie corresponde al dia que
 * dice corresponder.
 *
 * <p>Si falla, se pierde un punto de una tabla derivada y nada mas; por eso se
 * registra el error y no se propaga, que tumbaria el scheduler para siempre.
 */
@Component
@ConditionalOnProperty(name = "kardo.risk-snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class RiskSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(RiskSnapshotScheduler.class);

    private final RiskHistoryService riskHistoryService;

    public RiskSnapshotScheduler(RiskHistoryService riskHistoryService) {
        this.riskHistoryService = riskHistoryService;
    }

    @Scheduled(cron = "${kardo.risk-snapshot.cron:0 50 23 * * ?}",
               zone = "${kardo.risk-snapshot.zone:America/Lima}")
    public void capture() {
        try {
            riskHistoryService.capture();
            log.info("Foto diaria del valor en riesgo capturada");
        } catch (RuntimeException ex) {
            log.error("No se pudo capturar la foto del valor en riesgo: {}", ex.getMessage(), ex);
        }
    }
}

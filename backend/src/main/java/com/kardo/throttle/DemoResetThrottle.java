package com.kardo.throttle;

import com.kardo.config.ThrottleProperties;
import com.kardo.exception.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Freno de {@code /demo/reset}.
 *
 * <p>Es el unico endpoint publico que <b>escribe</b>: borra y resiembra la
 * tienda 999 en cada llamada, tomando un candado sobre ella. Invocarlo en bucle
 * no roba nada, pero consume el plan de Supabase que paga el dueño del
 * proyecto, y ese es el ataque mas barato que tiene este sistema.
 *
 * <p>Dos frenos distintos:
 * <ul>
 *   <li>Un <b>intervalo global</b>, porque la tienda demo es una sola: dos
 *       evaluadores reiniciandola a la vez se pisan el escenario. Esto ya era
 *       molesto antes de ser un riesgo.</li>
 *   <li>Un <b>limite por IP</b> mas generoso, para que un evaluador pueda
 *       recorrer la demo varias veces sin pelearse con el freno.</li>
 * </ul>
 */
@Component
public class DemoResetThrottle {

    private static final Logger log = LoggerFactory.getLogger(DemoResetThrottle.class);

    private final AtomicReference<Instant> lastReset = new AtomicReference<>(Instant.EPOCH);
    private final AttemptWindow byIp;
    private final Duration minInterval;
    private final Clock clock;

    public DemoResetThrottle(ThrottleProperties properties, Clock clock) {
        ThrottleProperties.Demo demo = properties.demo();
        this.byIp = new AttemptWindow(demo.maxPerIp(), demo.window(), clock);
        this.minInterval = demo.minInterval();
        this.clock = clock;
    }

    /**
     * Reserva el derecho a reiniciar.
     *
     * <p>El intervalo global se comprueba con un {@code compareAndSet} sobre el
     * instante del ultimo reinicio: dos llamadas simultaneas no pueden pasar
     * las dos, que es justo lo que ocurriria leyendo y escribiendo por separado.
     *
     * @throws TooManyAttemptsException si hay que esperar
     */
    public void checkAllowed(String clientIp) {
        Duration porIp = byIp.retryAfter(clientIp);
        if (!porIp.isZero()) {
            log.warn("Reinicio de la demo frenado para {}: demasiados en poco tiempo", clientIp);
            throw new TooManyAttemptsException(
                    "Has reiniciado la demo varias veces seguidas. Espera " + segundos(porIp) + ".", porIp);
        }

        Instant now = clock.instant();
        Instant previous = lastReset.get();
        Duration since = Duration.between(previous, now);

        if (since.compareTo(minInterval) < 0) {
            Duration espera = minInterval.minus(since);
            throw new TooManyAttemptsException(
                    "La demo se restauro hace " + segundos(since)
                            + ". Espera " + segundos(espera) + " para volver a hacerlo.", espera);
        }

        if (!lastReset.compareAndSet(previous, now)) {
            // Otro reinicio gano la carrera entre la comprobacion y aqui.
            throw new TooManyAttemptsException(
                    "Otro usuario esta restaurando la demo en este momento. Prueba en unos segundos.",
                    minInterval);
        }

        byIp.record(clientIp);
    }

    /** "1 segundo" y no "1 segundos": el mensaje lo lee una persona. */
    private static String segundos(Duration duracion) {
        long valor = Math.max(1, duracion.toSeconds());
        return valor + (valor == 1 ? " segundo" : " segundos");
    }
}

package com.kardo.throttle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cuenta intentos por clave dentro de una ventana de tiempo.
 *
 * <p>Vive en memoria <b>a proposito</b>. Un freno que escribe en la base en cada
 * intento convierte el ataque en una factura de Supabase: cuanto mas insiste el
 * atacante, mas cuesta defenderse. Perder los contadores en un reinicio es un
 * precio menor, y ademas no es algo que el atacante pueda provocar.
 *
 * <p>El mapa esta acotado. En 512 MB, un contador por clave sin limite es otra
 * forma del mismo problema: bastaria rotar correos inventados para llenar el
 * heap. Al llegar al tope se purga lo caducado, y si aun asi no cabe, se deja de
 * admitir claves nuevas; las que ya estan bloqueadas siguen bloqueadas, que es
 * lo que importa conservar.
 */
public class AttemptWindow {

    /** Claves vivas como maximo; por encima, el mapa deja de crecer. */
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Duration window;
    private final int limit;
    private final Clock clock;

    public AttemptWindow(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Registra un intento.
     *
     * @return cuanto falta para poder reintentar, o {@link Duration#ZERO} si aun
     *         queda margen
     */
    public Duration record(String key) {
        Instant now = clock.instant();
        Counter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expired(now, window)) {
                return new Counter(now);
            }
            existing.attempts.incrementAndGet();
            return existing;
        });

        evictIfCrowded(now);

        return remaining(counter, now);
    }

    /** Consulta sin gastar un intento: para rechazar antes de hacer el trabajo. */
    public Duration retryAfter(String key) {
        Counter counter = counters.get(key);
        if (counter == null) {
            return Duration.ZERO;
        }
        return remaining(counter, clock.instant());
    }

    /**
     * Borra el contador de una clave.
     *
     * <p>Lo llama el acierto: quien se equivoca dos veces y a la tercera entra no
     * tiene por que arrastrar un bloqueo. Sin esto, el freno castigaria sobre
     * todo a los usuarios torpes, que son muchos mas que los atacantes.
     */
    public void clear(String key) {
        counters.remove(key);
    }

    /** Claves bloqueadas ahora mismo; lo publica el panel de diagnostico. */
    public long blockedNow() {
        Instant now = clock.instant();
        return counters.values().stream()
                .filter(counter -> !counter.expired(now, window) && counter.attempts.get() >= limit)
                .count();
    }

    private Duration remaining(Counter counter, Instant now) {
        if (counter.expired(now, window) || counter.attempts.get() < limit) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, counter.startedAt.plus(window));
        return left.isNegative() ? Duration.ZERO : left;
    }

    private void evictIfCrowded(Instant now) {
        if (counters.size() <= MAX_KEYS) {
            return;
        }
        counters.values().removeIf(counter -> counter.expired(now, window));
    }

    /** Ventana fija que arranca con el primer intento. */
    private static final class Counter {
        private final Instant startedAt;
        private final AtomicInteger attempts = new AtomicInteger(1);

        private Counter(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean expired(Instant now, Duration window) {
            return now.isAfter(startedAt.plus(window));
        }
    }
}

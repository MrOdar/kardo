package com.kardo.throttle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.config.ThrottleProperties;
import com.kardo.exception.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El freno del reinicio de la demo.
 *
 * <p>Es el unico endpoint publico que escribe, y lo que escribe es caro: borra
 * y resiembra la tienda entera tomando un candado sobre ella.
 */
class DemoResetThrottleTest {

    private MutableClock clock;
    private DemoResetThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        throttle = nuevo(Duration.ofSeconds(20), 10);
    }

    @Test
    @DisplayName("Dos reinicios seguidos no pasan: la tienda demo es una sola")
    void intervaloGlobal() {
        throttle.checkAllowed("190.12.0.1");

        assertThatThrownBy(() -> throttle.checkAllowed("190.12.0.2"))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("se restauro hace");
    }

    @Test
    @DisplayName("Pasado el intervalo, se puede volver a reiniciar")
    void pasadoElIntervalo() {
        throttle.checkAllowed("190.12.0.1");
        clock.advance(Duration.ofSeconds(21));

        assertThatCode(() -> throttle.checkAllowed("190.12.0.2")).doesNotThrowAnyException();
    }

    /** El evaluador debe poder recorrer la demo varias veces sin pelearse. */
    @Test
    @DisplayName("Un evaluador puede reiniciar varias veces si espera entre una y otra")
    void elEvaluadorNoSePelea() {
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> throttle.checkAllowed("190.12.0.1")).doesNotThrowAnyException();
            clock.advance(Duration.ofSeconds(21));
        }
    }

    @Test
    @DisplayName("Insistir desde la misma IP acaba frenando aunque se respete el intervalo")
    void topePorIp() {
        DemoResetThrottle corto = nuevo(Duration.ofSeconds(1), 3);

        for (int i = 0; i < 3; i++) {
            corto.checkAllowed("190.12.0.1");
            clock.advance(Duration.ofSeconds(2));
        }

        assertThatThrownBy(() -> corto.checkAllowed("190.12.0.1"))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("varias veces seguidas");
    }

    /**
     * El caso que justifica el {@code compareAndSet}: leyendo y escribiendo por
     * separado, dos llamadas simultaneas pasarian las dos y el procedimiento se
     * ejecutaria dos veces sobre la misma tienda.
     */
    @Test
    @DisplayName("De diez reinicios simultaneos solo pasa uno")
    void unoSoloGanaLaCarrera() throws Exception {
        DemoResetThrottle compartido = nuevo(Duration.ofSeconds(20), 100);

        List<Callable<Boolean>> intentos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int indice = i;
            intentos.add(() -> {
                try {
                    compartido.checkAllowed("190.12.0." + indice);
                    return true;
                } catch (TooManyAttemptsException frenado) {
                    return false;
                }
            });
        }

        long aceptados;
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            aceptados = pool.invokeAll(intentos).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();
        }

        org.assertj.core.api.Assertions.assertThat(aceptados).isEqualTo(1);
    }

    private DemoResetThrottle nuevo(Duration intervalo, int porIp) {
        return new DemoResetThrottle(new ThrottleProperties(
                new ThrottleProperties.Login(5, 20, Duration.ofMinutes(15)),
                new ThrottleProperties.Demo(intervalo, porIp, Duration.ofMinutes(10)),
                new ThrottleProperties.Registration(3, Duration.ofHours(1))),
                clock);
    }

    /** Reloj que se puede adelantar, para no dormir la suite. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

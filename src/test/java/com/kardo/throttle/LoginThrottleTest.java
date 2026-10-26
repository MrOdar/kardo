package com.kardo.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.config.ThrottleProperties;
import com.kardo.exception.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El freno de fuerza bruta sobre el login.
 *
 * <p>El reloj se adelanta a mano en lugar de dormir el hilo: una prueba honesta
 * de una ventana de quince minutos no puede durar quince minutos.
 */
class LoginThrottleTest {

    private static final String IP = "190.12.0.1";
    private static final String CUENTA = "gerente@kardo.pe";

    private MutableClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        throttle = new LoginThrottle(new ThrottleProperties(
                new ThrottleProperties.Login(3, 8, Duration.ofMinutes(15)),
                new ThrottleProperties.Demo(Duration.ofSeconds(20), 10, Duration.ofMinutes(10))),
                clock);
    }

    @Test
    @DisplayName("Tras agotar los intentos de la cuenta, el siguiente ni se atiende")
    void frenaPorCuenta() {
        for (int i = 0; i < 3; i++) {
            throttle.checkAllowed(CUENTA, IP);
            throttle.recordFailure(CUENTA, IP);
        }

        assertThatThrownBy(() -> throttle.checkAllowed(CUENTA, IP))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("Demasiados intentos");
    }

    /**
     * Sin limite por IP, un barrido de correos desde una sola maquina no tocaria
     * nunca el limite por cuenta: cada correo empieza de cero.
     */
    @Test
    @DisplayName("Probar muchos correos distintos desde una IP tambien se frena")
    void frenaPorIpAunqueCambieElCorreo() {
        for (int i = 0; i < 8; i++) {
            String otro = "usuario" + i + "@kardo.pe";
            throttle.checkAllowed(otro, IP);
            throttle.recordFailure(otro, IP);
        }

        assertThatThrownBy(() -> throttle.checkAllowed("usuario99@kardo.pe", IP))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /**
     * Si solo frenara por IP, repartir el ataque bastaria para seguir probando
     * contra la misma cuenta.
     */
    @Test
    @DisplayName("Un ataque repartido entre muchas IP sigue frenado por la cuenta")
    void elAtaqueRepartidoNoEsquivaElLimite() {
        for (int i = 0; i < 3; i++) {
            String desdeOtraIp = "190.12.0." + (100 + i);
            throttle.checkAllowed(CUENTA, desdeOtraIp);
            throttle.recordFailure(CUENTA, desdeOtraIp);
        }

        assertThatThrownBy(() -> throttle.checkAllowed(CUENTA, "190.12.0.200"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    /**
     * Sin esto, el freno castigaria sobre todo a los usuarios torpes, que son
     * muchisimos mas que los atacantes.
     */
    @Test
    @DisplayName("Acertar borra los fallos anteriores")
    void elAciertoLimpia() {
        throttle.recordFailure(CUENTA, IP);
        throttle.recordFailure(CUENTA, IP);
        throttle.recordSuccess(CUENTA, IP);

        throttle.recordFailure(CUENTA, IP);
        throttle.recordFailure(CUENTA, IP);

        assertThatCode(() -> throttle.checkAllowed(CUENTA, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Pasada la ventana se vuelve a poder intentar")
    void laVentanaCaduca() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(CUENTA, IP);
        }
        assertThatThrownBy(() -> throttle.checkAllowed(CUENTA, IP))
                .isInstanceOf(TooManyAttemptsException.class);

        clock.advance(Duration.ofMinutes(16));

        assertThatCode(() -> throttle.checkAllowed(CUENTA, IP)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("El rechazo dice cuanto falta, para no tener que sondear")
    void diceCuantoFalta() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(CUENTA, IP);
        }
        clock.advance(Duration.ofMinutes(5));

        var error = org.assertj.core.api.Assertions
                .catchThrowableOfType(() -> throttle.checkAllowed(CUENTA, IP), TooManyAttemptsException.class);

        assertThat(error.retryAfterSeconds()).isBetween(590L, 610L);
    }

    /** Un mensaje distinto para un correo real seria un buscador de usuarios. */
    @Test
    @DisplayName("El mensaje no revela si la cuenta existe")
    void noRevelaSiLaCuentaExiste() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("existe@kardo.pe", IP);
        }
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("inventado@kardo.pe", "190.12.0.9");
        }

        String real = mensaje("existe@kardo.pe", IP);
        String falso = mensaje("inventado@kardo.pe", "190.12.0.9");

        assertThat(real).isEqualTo(falso);
    }

    @Test
    @DisplayName("Da igual como se escriba el correo: el freno es el mismo")
    void elCorreoSeNormaliza() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(CUENTA, IP);
        }

        assertThatThrownBy(() -> throttle.checkAllowed("  Gerente@Kardo.PE  ", "190.12.0.77"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("Los bloqueos vivos se pueden contar, que es como se ve un ataque")
    void sePuedenContarLosBloqueos() {
        assertThat(throttle.blockedNow()).isZero();

        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(CUENTA, IP);
        }

        // La cuenta queda frenada; la IP aun no llega a sus ocho
        assertThat(throttle.blockedNow()).isEqualTo(1);
    }

    private String mensaje(String email, String ip) {
        return org.assertj.core.api.Assertions
                .catchThrowableOfType(() -> throttle.checkAllowed(email, ip), TooManyAttemptsException.class)
                .getMessage();
    }

    /** Reloj que se puede adelantar, para no dormir la suite. */
    private static final class MutableClock extends Clock {
        private Instant now;

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
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

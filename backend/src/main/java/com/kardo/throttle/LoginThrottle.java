package com.kardo.throttle;

import com.kardo.config.ThrottleProperties;
import com.kardo.exception.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Freno de fuerza bruta sobre {@code /auth/login}.
 *
 * <p>Hasta ahora el login aceptaba intentos ilimitados contra unas credenciales
 * que, ademas, estan publicadas en el repositorio para que el evaluador entre.
 * Eso convertia la unica puerta con contraseña del sistema en algo que se podia
 * abrir a base de insistir.
 *
 * <p>Se cuenta <b>por cuenta y por IP</b>, porque cada uno tapa el agujero del
 * otro: solo por cuenta, un ataque repartido entre muchas IP sigue frenado pero
 * un barrido de correos desde una sola IP pasa sin tocar ningun limite; solo por
 * IP, basta repartir el ataque para volver a tener via libre.
 *
 * <p>La IP sale de {@code getRemoteAddr()}, que en Render es la real gracias a
 * {@code server.forward-headers-strategy=framework}. Aun asi, una cabecera
 * {@code X-Forwarded-For} falsificada podria esquivar el limite por IP: por eso
 * el limite por cuenta es el que de verdad protege a un usuario concreto, y no
 * un adorno.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final AttemptWindow byAccount;
    private final AttemptWindow byIp;

    public LoginThrottle(ThrottleProperties properties, Clock clock) {
        ThrottleProperties.Login login = properties.login();
        this.byAccount = new AttemptWindow(login.maxPerAccount(), login.window(), clock);
        this.byIp = new AttemptWindow(login.maxPerIp(), login.window(), clock);
    }

    /**
     * Se llama antes de comprobar la contraseña.
     *
     * <p>Rechazar antes de verificar ahorra el BCrypt, que cuesta cien
     * milisegundos a proposito: si cada intento bloqueado siguiera gastandolos,
     * el propio freno seria la forma mas comoda de tumbar el servicio.
     *
     * @throws TooManyAttemptsException si la cuenta o la IP estan frenadas
     */
    public void checkAllowed(String email, String clientIp) {
        Duration cuenta = byAccount.retryAfter(key(email));
        Duration ip = byIp.retryAfter(clientIp);
        Duration espera = cuenta.compareTo(ip) >= 0 ? cuenta : ip;

        if (!espera.isZero()) {
            throw tooMany(espera);
        }
    }

    /** Un fallo suma; al llegar al tope, el siguiente intento ya no se atiende. */
    public void recordFailure(String email, String clientIp) {
        Duration cuenta = byAccount.record(key(email));
        Duration ip = byIp.record(clientIp);

        if (!cuenta.isZero() || !ip.isZero()) {
            // Se registra la cuenta porque es el dato util para avisar a su
            // dueño; la contraseña probada no se toca ni en el log.
            log.warn("Login frenado para {} desde {}: demasiados intentos fallidos", key(email), clientIp);
        }
    }

    /**
     * Un acierto borra el historial de esa cuenta y esa IP.
     *
     * <p>Quien se equivoca dos veces y a la tercera entra no arrastra el
     * bloqueo: sin esto, el freno castigaria sobre todo a los usuarios torpes,
     * que son muchisimos mas que los atacantes.
     */
    public void recordSuccess(String email, String clientIp) {
        byAccount.clear(key(email));
        byIp.clear(clientIp);
    }

    /** Cuentas e IP frenadas ahora mismo; lo muestra el panel de diagnostico. */
    public long blockedNow() {
        return byAccount.blockedNow() + byIp.blockedNow();
    }

    private static TooManyAttemptsException tooMany(Duration espera) {
        long segundos = Math.max(1, espera.toSeconds());
        return new TooManyAttemptsException(
                "Demasiados intentos fallidos. Vuelve a probar en " + segundos
                        + (segundos == 1 ? " segundo." : " segundos."), espera);
    }

    /** El correo se normaliza igual que en el login, o el freno seria esquivable. */
    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

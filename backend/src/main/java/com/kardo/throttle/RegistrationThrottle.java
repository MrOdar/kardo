package com.kardo.throttle;

import com.kardo.config.ThrottleProperties;
import com.kardo.exception.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Freno de {@code /auth/register}.
 *
 * <p>Es el tercer endpoint publico que escribe, y el unico que crea filas
 * permanentes: una empresa, una tienda y un usuario que nadie borra despues. En
 * un despliegue de capa gratuita, un guion dandole en bucle no roba datos pero
 * llena la base que paga el dueño del proyecto.
 *
 * <p>El limite es por IP y deliberadamente bajo: dar de alta una empresa es
 * algo que se hace una vez, no tres veces por hora. Quien tenga que crear mas
 * tiendas o mas usuarios lo hace ya autenticado, por otra puerta.
 */
@Component
public class RegistrationThrottle {

    private static final Logger log = LoggerFactory.getLogger(RegistrationThrottle.class);

    private final AttemptWindow byIp;

    public RegistrationThrottle(ThrottleProperties properties, Clock clock) {
        ThrottleProperties.Registration registration = properties.registration();
        this.byIp = new AttemptWindow(registration.maxPerIp(), registration.window(), clock);
    }

    /** @throws TooManyAttemptsException si esa IP ya dio de alta su cupo */
    public void checkAllowed(String clientIp) {
        Duration espera = byIp.retryAfter(clientIp);
        if (!espera.isZero()) {
            log.warn("Registro frenado para {}: demasiadas altas en poco tiempo", clientIp);
            throw new TooManyAttemptsException(
                    "Se han dado de alta varias empresas desde aqui hace poco. "
                            + "Vuelve a intentarlo en " + minutos(espera) + ".", espera);
        }
    }

    /**
     * Solo cuenta el alta que de verdad ocurrio.
     *
     * <p>Si se contara el intento, un RUC repetido o una contraseña corta
     * gastarian cupo, y quien se equivoca al teclear acabaria bloqueado sin
     * haber creado nada.
     */
    public void recordSuccess(String clientIp) {
        byIp.record(clientIp);
    }

    private static String minutos(Duration duracion) {
        long valor = Math.max(1, duracion.toMinutes());
        return valor + (valor == 1 ? " minuto" : " minutos");
    }
}

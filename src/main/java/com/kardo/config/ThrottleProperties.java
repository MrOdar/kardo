package com.kardo.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Frenos de los dos endpoints publicos (prefijo {@code kardo.throttle}).
 *
 * <p>Son los unicos que se pueden invocar sin token, y cada uno esta expuesto a
 * una cosa distinta: el login a que prueben contraseñas, y el reinicio de la
 * demo a que lo llamen en bucle, que borra y resiembra la tienda en cada
 * llamada.
 */
@ConfigurationProperties(prefix = "kardo.throttle")
public record ThrottleProperties(Login login, Demo demo, Registration registration) {

    /**
     * @param maxPerAccount intentos fallidos sobre un mismo correo antes de
     *                      frenar; protege a una cuenta concreta de un ataque
     *                      repartido entre muchas IP
     * @param maxPerIp      intentos fallidos desde una misma IP, contando todos
     *                      los correos: sin esto, bastaria rotar de usuario para
     *                      no tocar nunca el limite por cuenta
     * @param window        tiempo durante el que se recuerdan los fallos
     */
    public record Login(int maxPerAccount, int maxPerIp, Duration window) {
    }

    /**
     * @param minInterval espera minima entre dos reinicios, de quien sea: la
     *                    tienda demo es una sola y restaurarla mientras otro la
     *                    esta mirando ya era molesto antes de ser un riesgo
     * @param maxPerIp    reinicios por IP dentro de la ventana
     */
    public record Demo(Duration minInterval, int maxPerIp, Duration window) {
    }

    /**
     * @param maxPerIp altas de empresa por IP dentro de la ventana; bajo a
     *                 proposito, porque dar de alta una empresa se hace una vez
     *                 y lo demas -tiendas, usuarios- ya va autenticado
     * @param window   tiempo durante el que se recuerdan las altas
     */
    public record Registration(int maxPerIp, Duration window) {
    }
}

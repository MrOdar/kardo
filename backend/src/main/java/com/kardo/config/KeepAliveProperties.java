package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Heartbeat anti-hibernacion (prefijo {@code kardo.keepalive}).
 *
 * @param enabled permite apagarlo en local o en tests
 * @param cron    expresion de 6 campos; por defecto el mediodia
 * @param zone    zona horaria con la que se evalua el cron
 */
@ConfigurationProperties(prefix = "kardo.keepalive")
public record KeepAliveProperties(boolean enabled, String cron, String zone) {
}

package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Preprocesado de la imagen antes de enviarla a Gemini (prefijo {@code kardo.vision}).
 *
 * @param maxDimension lado mayor en pixeles; 1800 px mantiene legibles fuentes de
 *                     6 pt en una factura A4 sin inflar el payload
 * @param jpegQuality  calidad JPEG entre 0 y 1
 * @param quota        tope diario de llamadas a la vision
 */
@ConfigurationProperties(prefix = "kardo.vision")
public record VisionProperties(int maxDimension, float jpegQuality, Quota quota) {

    /**
     * Dos topes, porque protegen cosas distintas.
     *
     * @param perStoreDaily reparto: que una tienda no se coma la cuota de las
     *                      demas, ni un script deje a nadie sin recepcion
     * @param globalDaily   techo del despliegue: la API key es una sola para
     *                      todas las tiendas y es la que de verdad se agota
     */
    public record Quota(int perStoreDaily, int globalDaily) {
    }
}

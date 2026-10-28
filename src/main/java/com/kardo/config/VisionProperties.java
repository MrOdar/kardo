package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Preprocesado de la imagen antes de enviarla a Gemini (prefijo {@code kardo.vision}).
 *
 * @param maxDimension lado mayor en pixeles; 1800 px mantiene legibles fuentes de
 *                     6 pt en una factura A4 sin inflar el payload
 * @param jpegQuality  calidad JPEG entre 0 y 1
 * @param maxPixels    tope de pixeles de la imagen de entrada, antes de
 *                     decodificarla. El limite del multipart son 10 MB, pero un
 *                     PNG se comprime muy bien: 60 KB de archivo pueden declarar
 *                     30.000 x 30.000 px, y descomprimirlos son 3,6 GB de
 *                     {@code int[]} en un contenedor de 512 MB. Una sola foto
 *                     asi tumba el proceso, de modo que esto no protege la
 *                     calidad del OCR sino la disponibilidad. 40 Mpx deja pasar
 *                     cualquier camara de telefono con margen.
 * @param quota        tope diario de llamadas a la vision
 */
@ConfigurationProperties(prefix = "kardo.vision")
public record VisionProperties(int maxDimension, float jpegQuality, long maxPixels, Quota quota) {

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

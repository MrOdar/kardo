package com.kardo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Preprocesado de la imagen antes de enviarla a Gemini (prefijo {@code kardo.vision}).
 *
 * @param maxDimension lado mayor en pixeles; 1800 px mantiene legibles fuentes de
 *                     6 pt en una factura A4 sin inflar el payload
 * @param jpegQuality  calidad JPEG entre 0 y 1
 */
@ConfigurationProperties(prefix = "kardo.vision")
public record VisionProperties(int maxDimension, float jpegQuality) {
}

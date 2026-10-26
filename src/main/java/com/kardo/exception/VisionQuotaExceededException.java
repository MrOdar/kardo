package com.kardo.exception;

/**
 * Se agoto el cupo diario de escaneos. Mapea a HTTP 429.
 *
 * <p>El mensaje dice cual de los dos topes se alcanzo y cuando se renueva,
 * porque no es lo mismo para el operario: si fue el de su tienda puede pedir que
 * se lo suban, y si fue el del despliegue no hay nada que pueda hacer hoy.
 */
public class VisionQuotaExceededException extends RuntimeException {

    public VisionQuotaExceededException(String message) {
        super(message);
    }

    public static VisionQuotaExceededException forStore(int limit) {
        return new VisionQuotaExceededException(
                "Esta tienda agoto sus " + limit + " escaneos de hoy. "
                        + "El cupo se renueva a medianoche; mientras tanto, la guia puede cargarse por Excel.");
    }

    public static VisionQuotaExceededException forDeployment(int limit) {
        return new VisionQuotaExceededException(
                "Se alcanzo el tope diario de " + limit + " escaneos de todo el sistema. "
                        + "El cupo se renueva a medianoche; mientras tanto, la guia puede cargarse por Excel.");
    }
}

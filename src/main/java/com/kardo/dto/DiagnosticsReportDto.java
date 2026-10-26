package com.kardo.dto;

import java.util.List;

/**
 * Respuesta de {@code /ops/diagnostics}: quien se esta llevando el tiempo.
 *
 * <p>La pregunta que contesta no es "cuantos milisegundos" sino "a quien hay
 * que mirar", asi que el veredicto va primero y en palabras. Los numeros estan
 * debajo para quien quiera comprobarlo.
 *
 * @param verdict    frase que nombra al sospechoso, o dice que no hay ninguno
 * @param suspects   Gemini y Supabase, los dos servicios de los que dependemos
 * @param pool       presion sobre el pool de conexiones, que no es latencia de
 *                   Supabase sino espera por una conexion libre
 * @param heapUsedMb memoria en uso: en 512 MB, un "resto" alto suele ser esto
 */
public record DiagnosticsReportDto(
        String verdict,
        List<SuspectDto> suspects,
        PoolDto pool,
        long heapUsedMb,
        long heapMaxMb) {

    /**
     * @param p95Millis nulo cuando aun no hay muestras suficientes; se dice en
     *                  lugar de inventar un cero que pareceria "va rapidisimo"
     */
    public record SuspectDto(
            String name,
            long calls,
            Long p50Millis,
            Long p95Millis,
            long maxMillis,
            String detail) {
    }

    /**
     * @param pendingThreads       hilos esperando una conexion ahora mismo; con
     *                             el pool en 5 y Supabase libre esto es 0
     * @param acquireTypicalMillis espera mediana por una conexion, que es la que
     *                             juzga al pool
     * @param acquireMaxMillis     peor espera registrada; incluye el coste de
     *                             abrir la primera conexion (TCP y TLS contra
     *                             Supabase), asi que sirve de contexto y no de
     *                             prueba
     */
    public record PoolDto(
            int active,
            int idle,
            int max,
            int pendingThreads,
            long acquireTypicalMillis,
            long acquireMaxMillis) {
    }
}

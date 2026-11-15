package com.kardo.diagnostics;

/**
 * Reparto del tiempo de UNA peticion entre sus sospechosos.
 *
 * <p>Las metricas agregadas dicen que el p95 de algo empeoro; esto dice de donde
 * salieron los cuatro segundos de <em>esta</em> guia. Sin ese reparto, un
 * operario que reporta lentitud obliga a adivinar entre Gemini, Supabase y el
 * pool, que es exactamente lo que no se podia hacer hasta ahora.
 *
 * <p>Vive en un {@link ThreadLocal} y no en un bean de ambito de peticion porque
 * lo escriben capas que no conocen la web -el {@code JdbcTemplate}, el cliente
 * de vision- y buscar el bean desde ahi las ataria al contenedor servlet.
 *
 * <p>El reparto distingue la <b>espera por conexion</b> de la <b>ejecucion de la
 * sentencia</b>: desde fuera se parecen, pero una acusa al pool y la otra a
 * Supabase, y confundirlas manda a depurar al sitio equivocado.
 */
public final class RequestDiagnostics {

    private static final ThreadLocal<RequestDiagnostics> CURRENT = new ThreadLocal<>();

    private final String requestId;
    private final long startedAtNanos = System.nanoTime();

    private long databaseNanos;
    private int databaseCalls;

    private long poolNanos;

    private long visionNanos;
    private int visionCalls;

    private RequestDiagnostics(String requestId) {
        this.requestId = requestId;
    }

    static RequestDiagnostics begin(String requestId) {
        RequestDiagnostics diagnostics = new RequestDiagnostics(requestId);
        CURRENT.set(diagnostics);
        return diagnostics;
    }

    static void end() {
        CURRENT.remove();
    }

    /** @return el acumulador de la peticion en curso, o {@code null} fuera de una */
    public static RequestDiagnostics current() {
        return CURRENT.get();
    }

    /** Suma una sentencia ejecutada contra Supabase. */
    public static void recordDatabase(long nanos) {
        RequestDiagnostics diagnostics = CURRENT.get();
        if (diagnostics != null) {
            diagnostics.databaseNanos += nanos;
            diagnostics.databaseCalls++;
        }
    }

    /** Suma lo que costo conseguir una conexion libre del pool. */
    public static void recordConnectionWait(long nanos) {
        RequestDiagnostics diagnostics = CURRENT.get();
        if (diagnostics != null) {
            diagnostics.poolNanos += nanos;
        }
    }

    public static void recordVision(long nanos) {
        RequestDiagnostics diagnostics = CURRENT.get();
        if (diagnostics != null) {
            diagnostics.visionNanos += nanos;
            diagnostics.visionCalls++;
        }
    }

    public String requestId() {
        return requestId;
    }

    public long totalMillis() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    public long databaseMillis() {
        return databaseNanos / 1_000_000;
    }

    public long poolMillis() {
        return poolNanos / 1_000_000;
    }

    public long visionMillis() {
        return visionNanos / 1_000_000;
    }

    public int databaseCalls() {
        return databaseCalls;
    }

    public int visionCalls() {
        return visionCalls;
    }

    /**
     * El reparto en una linea, pensada para leerse en los logs de Render.
     *
     * <p>Se nombra lo que no se midio ("resto") en lugar de dejarlo implicito:
     * un resto grande es la pista de que el problema no es ninguno de los dos
     * sospechosos habituales sino el propio contenedor.
     */
    public String breakdown() {
        long total = totalMillis();
        long rest = Math.max(0, total - databaseMillis() - visionMillis() - poolMillis());

        StringBuilder detail = new StringBuilder(total + " ms");
        if (visionCalls > 0) {
            detail.append(" · vision ").append(visionMillis()).append(" ms");
        }
        if (databaseCalls > 0) {
            detail.append(" · bd ").append(databaseMillis()).append(" ms en ")
                    .append(databaseCalls).append(databaseCalls == 1 ? " consulta" : " consultas");
        }
        // El pool solo aparece cuando costo algo: en condiciones normales es 0 y
        // nombrarlo en cada linea solo haria mas dificil ver lo que si importa.
        if (poolMillis() > 0) {
            detail.append(" · pool ").append(poolMillis()).append(" ms");
        }
        return detail.append(" · resto ").append(rest).append(" ms").toString();
    }
}

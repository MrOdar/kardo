package com.kardo.diagnostics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * {@code DataSource} que cronometra lo que de verdad tarda Supabase.
 *
 * <p>Se mide aqui, a ras de JDBC, despues de haberlo intentado mas arriba y
 * comprobado que no funciona: sobrescribir los {@code execute} de
 * {@code JdbcTemplate} no intercepta nada, porque Spring delega internamente en
 * sobrecargas privadas que no vuelven a pasar por los metodos publicos. El
 * cronometro marcaba cero consultas con la aplicacion funcionando, que es la
 * peor clase de metrica: una que tranquiliza.
 *
 * <p>Aqui pasa todo por construccion, venga de donde venga, y se separan las dos
 * cosas que desde fuera parecen la misma:
 * <ul>
 *   <li><b>Esperar una conexion</b> ({@code getConnection}) es el pool: hay
 *       cinco y alguien las esta reteniendo.</li>
 *   <li><b>Ejecutar la sentencia</b> es Supabase, o la red hasta ella.</li>
 * </ul>
 * Confundirlas lleva a optimizar consultas cuando el problema es que no quedan
 * conexiones libres.
 */
public class MeasuredDataSource extends DelegatingDataSource {

    /** Tiempo de ejecucion de sentencias; lo lee el panel de diagnostico. */
    public static final String STATEMENT_TIMER = "kardo.db.statement";

    /** Espera por una conexion libre del pool. */
    public static final String CONNECTION_TIMER = "kardo.db.connection";

    private final Timer statementTimer;
    private final Timer connectionTimer;

    public MeasuredDataSource(DataSource target, MeterRegistry registry) {
        super(target);
        this.statementTimer = Timer.builder(STATEMENT_TIMER)
                .description("Tiempo de ejecucion de sentencias SQL contra Supabase")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.connectionTimer = Timer.builder(CONNECTION_TIMER)
                .description("Espera por una conexion libre del pool")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    @Override
    public Connection getConnection() throws SQLException {
        long startedAt = System.nanoTime();
        try {
            return wrap(super.getConnection());
        } finally {
            RequestDiagnostics.recordConnectionWait(record(connectionTimer, startedAt));
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        long startedAt = System.nanoTime();
        try {
            return wrap(super.getConnection(username, password));
        } finally {
            RequestDiagnostics.recordConnectionWait(record(connectionTimer, startedAt));
        }
    }

    /**
     * Envuelve la conexion para poder envolver sus sentencias.
     *
     * <p>Un proxy dinamico y no un delegado escrito a mano: {@code Connection}
     * tiene mas de cincuenta metodos y aqui solo importan los tres que crean
     * sentencias. Delegar los otros cuarenta y siete seria ruido donde esconder
     * un error.
     */
    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    return switch (method.getName()) {
                        case "createStatement" -> wrapStatement(result, Statement.class);
                        case "prepareStatement" -> wrapStatement(result, PreparedStatement.class);
                        case "prepareCall" -> wrapStatement(result, CallableStatement.class);
                        default -> result;
                    };
                });
    }

    /**
     * Cronometra cualquier metodo {@code execute*} de la sentencia.
     *
     * <p>Por prefijo y no por lista cerrada: {@code execute},
     * {@code executeQuery}, {@code executeUpdate}, {@code executeBatch} y
     * {@code executeLargeUpdate} son todos SQL saliendo hacia Supabase, y una
     * lista cerrada se quedaria corta en cuanto el driver añadiese uno.
     */
    private Object wrapStatement(Object statement, Class<?> type) {
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (!method.getName().startsWith("execute")) {
                        return invoke(statement, method, args);
                    }
                    long startedAt = System.nanoTime();
                    try {
                        return invoke(statement, method, args);
                    } finally {
                        RequestDiagnostics.recordDatabase(record(statementTimer, startedAt));
                    }
                });
    }

    /**
     * Publica la medicion y devuelve lo que tardo.
     *
     * <p>La metrica se registra siempre, tambien fuera de una peticion -como en
     * el heartbeat diario-; el reparto por peticion lo decide quien recibe este
     * valor.
     */
    private static long record(Timer timer, long startedAtNanos) {
        long elapsed = System.nanoTime() - startedAtNanos;
        timer.record(elapsed, TimeUnit.NANOSECONDS);
        return elapsed;
    }

    /** Propaga la excepcion original y no el envoltorio de la reflexion. */
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}

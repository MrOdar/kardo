package com.kardo.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.exception.VisionQuotaExceededException;
import com.kardo.vision.VisionQuotaService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * El tope de escaneos contra PostgreSQL real.
 *
 * <p>Un tope solo sirve si aguanta lo que de verdad lo pone a prueba: dos
 * peticiones a la vez. Por eso aqui no basta con llamar diez veces en fila.
 */
@TestPropertySource(properties = {
        "kardo.vision.quota.per-store-daily=5",
        "kardo.vision.quota.global-daily=8"
})
class VisionQuotaIT extends PostgresIntegrationTest {

    @Autowired
    private VisionQuotaService quotaService;

    private Fixtures fixtures;
    private long storeId;

    @BeforeEach
    void seed() {
        // Unica clase que limpia una tabla, y por un motivo concreto: el tope
        // del despliegue suma TODAS las tiendas del dia, de modo que el consumo
        // que dejase otra prueba seria indistinguible del real y haria fallar a
        // esta segun el orden en que corrieran.
        jdbcTemplate.update("DELETE FROM vision_usage");

        fixtures = new Fixtures(jdbcTemplate);
        storeId = fixtures.company("Minimarket Cuota", "21400000016").store("TIENDA-CU");
    }

    @Test
    @DisplayName("Cada escaneo descuenta uno y el saldo lo dice")
    void descuenta() {
        assertThat(quotaService.status(storeId).remainingToday()).isEqualTo(5);

        var primera = quotaService.reserve(storeId, "operario@kardo.pe");

        assertThat(primera.usedToday()).isEqualTo(1);
        assertThat(primera.remainingToday()).isEqualTo(4);
        assertThat(quotaService.status(storeId).remainingToday()).isEqualTo(4);
    }

    @Test
    @DisplayName("Agotado el cupo de la tienda, el escaneo se rechaza")
    void topePorTienda() {
        for (int i = 0; i < 5; i++) {
            quotaService.reserve(storeId, "operario@kardo.pe");
        }

        assertThatThrownBy(() -> quotaService.reserve(storeId, "operario@kardo.pe"))
                .isInstanceOf(VisionQuotaExceededException.class)
                .hasMessageContaining("5 escaneos");

        // Y el rechazo no incrementa el contador: el operario no se queda en
        // negativo por haber insistido
        assertThat(quotaService.status(storeId).usedToday()).isEqualTo(5);
    }

    /**
     * El caso que justifica que la reserva sea un solo {@code INSERT … ON
     * CONFLICT … WHERE}: diez hilos leyendo y escribiendo un contador por
     * separado se pasarian del tope.
     */
    @Test
    @DisplayName("Diez escaneos simultaneos no se pasan del tope")
    void concurrencia() throws Exception {
        List<Callable<Boolean>> intentos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            intentos.add(() -> {
                try {
                    quotaService.reserve(storeId, "operario@kardo.pe");
                    return true;
                } catch (VisionQuotaExceededException agotado) {
                    return false;
                }
            });
        }

        int aceptados;
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            aceptados = (int) pool.invokeAll(intentos).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();
        }

        assertThat(aceptados).isEqualTo(5);
        assertThat(quotaService.status(storeId).usedToday()).isEqualTo(5);
    }

    @Test
    @DisplayName("El cupo de una tienda no se gasta con el de otra")
    void cupoPorTienda() {
        long otra = fixtures.company("Minimarket Vecino Cuota", "21400000017").store("TIENDA-VC");

        quotaService.reserve(storeId, "operario@kardo.pe");
        quotaService.reserve(storeId, "operario@kardo.pe");

        assertThat(quotaService.status(otra).remainingToday()).isEqualTo(5);
        assertThat(quotaService.status(otra).usedToday()).isZero();
    }

    /**
     * La API key es una sola para todo el despliegue: el tope por tienda reparte,
     * pero el que de verdad protege la cuota de Google es este.
     */
    @Test
    @DisplayName("El tope global corta aunque a la tienda le quede saldo")
    void topeGlobal() {
        long a = fixtures.company("Cuota Global A", "21400000018").store("TIENDA-GA");
        long b = fixtures.company("Cuota Global B", "21400000019").store("TIENDA-GB");

        for (int i = 0; i < 4; i++) {
            quotaService.reserve(a, "operario@kardo.pe");
        }
        for (int i = 0; i < 4; i++) {
            quotaService.reserve(b, "operario@kardo.pe");
        }

        // A la tienda b le queda saldo propio (4 de 5), pero el despliegue no
        assertThat(quotaService.status(b).remainingToday()).isEqualTo(1);
        assertThatThrownBy(() -> quotaService.reserve(b, "operario@kardo.pe"))
                .isInstanceOf(VisionQuotaExceededException.class)
                .hasMessageContaining("todo el sistema");
    }

    @Test
    @DisplayName("El consumo se imputa al dia de Lima, no al del servidor en UTC")
    void diaDeLima() {
        quotaService.reserve(storeId, "operario@kardo.pe");

        LocalDate hoyEnLima = LocalDate.now(ZoneId.of("America/Lima"));

        assertThat(quotaService.status(storeId).day()).isEqualTo(hoyEnLima);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_date FROM vision_usage WHERE store_id = ?", LocalDate.class, storeId))
                .isEqualTo(hoyEnLima);
    }

    @Test
    @DisplayName("Queda quien pidio el ultimo escaneo, para poder mirar quien gasta")
    void rastroDeConsumo() {
        quotaService.reserve(storeId, "operario@kardo.pe");
        quotaService.reserve(storeId, "gerente@kardo.pe");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_scan_by || '|' || scans FROM vision_usage WHERE store_id = ?
                """, String.class, storeId))
                .isEqualTo("gerente@kardo.pe|2");
    }
}

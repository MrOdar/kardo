package com.kardo.waste;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.exception.BatchNotFoundException;
import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.WasteAlreadyResolvedException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Los procedimientos de merma deciden las reglas; aqui se comprueba que sus
 * ERRCODE llegan al operario como el HTTP correcto y no como un 500.
 */
class WasteServiceTest {

    @Test
    @DisplayName("P0005: el lote no tiene suficientes unidades disponibles")
    void sinUnidadesSuficientes() {
        var translated = WasteService.translate(
                error("P0005", "ERROR: El lote 61 no tiene 99 unidades disponibles."), 61L, 0L);

        assertThat(translated).isInstanceOf(InsufficientStockException.class);
        assertThat(translated.getMessage())
                .isEqualTo("El lote 61 no tiene 99 unidades disponibles.")
                .doesNotContain("ERROR:");
    }

    @Test
    @DisplayName("P0006: el lote no es de esta tienda")
    void loteAjeno() {
        assertThat(WasteService.translate(
                error("P0006", "ERROR: El lote 999999 no existe en la tienda 999."), 999999L, 0L))
                .isInstanceOf(BatchNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    @DisplayName("P0007: resolver dos veces la misma merma no la aplica dos veces")
    void resolucionIdempotente() {
        assertThat(WasteService.translate(
                error("P0007", "ERROR: La merma 1 no existe o ya fue resuelta."), 0L, 1L))
                .isInstanceOf(WasteAlreadyResolvedException.class)
                .hasMessageContaining("ya fue resuelta");
    }

    @Test
    @DisplayName("P0004: cantidad no positiva")
    void cantidadInvalida() {
        assertThat(WasteService.translate(
                error("P0004", "ERROR: La cantidad a dar de baja debe ser mayor que 0 (recibido: 0)."), 61L, 0L))
                .isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    @DisplayName("Un fallo ajeno al procedimiento se propaga tal cual")
    void erroresAjenos() {
        DataAccessException original = error("08006", "ERROR: connection failure");

        assertThat(WasteService.translate(original, 61L, 0L)).isSameAs(original);
    }

    private static DataAccessException error(String sqlState, String message) {
        return new UncategorizedSQLException("CALL sp_report_waste(...)", null,
                new SQLException(message, sqlState));
    }
}

package com.kardo.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.exception.InsufficientStockException;
import com.kardo.exception.InvalidSaleException;
import com.kardo.exception.ProductNotFoundException;
import com.kardo.exception.StoreNotFoundException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Los ERRCODE que levanta {@code sp_process_fefo_sale} deben llegar al operario
 * como el HTTP correcto; sin esta traduccion todos serian un 500 opaco.
 */
class FefoSaleErrorTranslatorTest {

    @Test
    @DisplayName("P0001: el SKU no esta en el catalogo de la tienda")
    void productoInexistente() {
        var translated = FefoSaleErrorTranslator.translate(
                error("P0001", "ERROR: Producto con SKU SKU-X no existe en el catalogo de la tienda 999."),
                "SKU-X", 999L);

        assertThat(translated).isInstanceOf(ProductNotFoundException.class);
        assertThat(translated.getMessage()).contains("SKU-X");
    }

    @Test
    @DisplayName("P0002: stock insuficiente, conservando el detalle de cuanto falto")
    void stockInsuficiente() {
        var translated = FefoSaleErrorTranslator.translate(
                error("P0002", "ERROR: Stock insuficiente para SKU SKU-LECHE. Faltaron 12 unidades."),
                "SKU-LECHE", 999L);

        assertThat(translated).isInstanceOf(InsufficientStockException.class);
        assertThat(translated.getMessage())
                .isEqualTo("Stock insuficiente para SKU SKU-LECHE. Faltaron 12 unidades.")
                // El prefijo de PostgreSQL no se le muestra al operario
                .doesNotContain("ERROR:");
    }

    @Test
    @DisplayName("P0003: la tienda no existe")
    void tiendaInexistente() {
        assertThat(FefoSaleErrorTranslator.translate(
                error("P0003", "ERROR: La tienda 123 no existe."), "SKU-X", 123L))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    @DisplayName("P0004: cantidad no positiva")
    void cantidadInvalida() {
        var translated = FefoSaleErrorTranslator.translate(
                error("P0004", "ERROR: La cantidad a vender debe ser mayor que 0 (recibido: 0)."),
                "SKU-X", 999L);

        assertThat(translated).isInstanceOf(InvalidSaleException.class);
        assertThat(translated.getMessage()).contains("mayor que 0");
    }

    @Test
    @DisplayName("Un fallo que no es del procedimiento se propaga tal cual")
    void erroresAjenosSePropagan() {
        DataAccessException original = error("08006", "ERROR: connection failure");

        assertThat(FefoSaleErrorTranslator.translate(original, "SKU-X", 999L)).isSameAs(original);
    }

    private static DataAccessException error(String sqlState, String message) {
        return new UncategorizedSQLException("CALL sp_process_fefo_sale(?, ?, ?, ?)", null,
                new SQLException(message, sqlState));
    }
}

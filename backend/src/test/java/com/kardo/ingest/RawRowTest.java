package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cubre los tipos que fastexcel entrega cuando la celda no es texto: fechas ya
 * convertidas a {@code LocalDateTime} y numeros de serie de Excel.
 */
class RawRowTest {

    @Test
    @DisplayName("Usa la fecha nativa de la celda sin volver a parsear texto")
    void fechaNativa() {
        RawRow row = row(Map.of(InventoryColumn.EXPIRATION_DATE, LocalDateTime.of(2027, 3, 15, 0, 0)));

        assertThat(row.date(InventoryColumn.EXPIRATION_DATE)).contains(LocalDate.of(2027, 3, 15));
        assertThat(row.text(InventoryColumn.EXPIRATION_DATE)).isEqualTo("2027-03-15");
    }

    @Test
    @DisplayName("Convierte el numero de serie de Excel a fecha calendario")
    void numeroDeSerie() {
        // 45000 corresponde al 15 de abril de 2023 en el calendario de Excel
        RawRow row = row(Map.of(InventoryColumn.EXPIRATION_DATE, new BigDecimal("45031")));

        assertThat(row.date(InventoryColumn.EXPIRATION_DATE)).contains(LocalDate.of(2023, 4, 15));
    }

    @Test
    @DisplayName("Tolera simbolos de moneda y separadores de miles")
    void montosConFormatoLocal() {
        assertThat(row(Map.of(InventoryColumn.UNIT_COST, "S/ 12,50")).number(InventoryColumn.UNIT_COST))
                .contains(new BigDecimal("12.50"));
        assertThat(row(Map.of(InventoryColumn.UNIT_COST, "1.234,50")).number(InventoryColumn.UNIT_COST))
                .contains(new BigDecimal("1234.50"));
        assertThat(row(Map.of(InventoryColumn.UNIT_COST, "1,234.50")).number(InventoryColumn.UNIT_COST))
                .contains(new BigDecimal("1234.50"));
        assertThat(row(Map.of(InventoryColumn.UNIT_COST, "sin precio")).number(InventoryColumn.UNIT_COST))
                .isEmpty();
    }

    @Test
    @DisplayName("Una fecha ilegible se distingue de una celda vacia")
    void fechaIlegibleVersusVacia() {
        RawRow ilegible = row(Map.of(InventoryColumn.EXPIRATION_DATE, "manana"));
        assertThat(ilegible.isBlank(InventoryColumn.EXPIRATION_DATE)).isFalse();
        assertThat(ilegible.date(InventoryColumn.EXPIRATION_DATE)).isEmpty();

        RawRow vacia = row(Map.of());
        assertThat(vacia.isBlank(InventoryColumn.EXPIRATION_DATE)).isTrue();
        assertThat(vacia.date(InventoryColumn.EXPIRATION_DATE)).isEmpty();
    }

    private static RawRow row(Map<InventoryColumn, Object> values) {
        return new RawRow(2, new EnumMap<>(values.isEmpty() ? new EnumMap<>(InventoryColumn.class) : values));
    }
}

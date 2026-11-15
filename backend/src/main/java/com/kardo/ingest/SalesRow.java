package com.kardo.ingest;

import java.time.LocalDate;

/**
 * Una venta leida del Excel, ya validada en lo que se puede validar sin base.
 *
 * @param rowNumber fila original del Excel, para poder señalarla en pantalla
 * @param saleDate  null significa "ahora": la celda venia vacia
 */
record SalesRow(int rowNumber, String sku, int quantity, LocalDate saleDate) {
}

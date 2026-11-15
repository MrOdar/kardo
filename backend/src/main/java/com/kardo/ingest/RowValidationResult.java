package com.kardo.ingest;

import com.kardo.dto.SpreadsheetRowDto;

/** Resultado de pasar una fila por la compuerta de calidad. */
public sealed interface RowValidationResult {

    record Valid(SpreadsheetRowDto row) implements RowValidationResult {
    }

    record Rejected(IngestErrorCode code) implements RowValidationResult {
    }
}

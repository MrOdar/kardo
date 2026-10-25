package com.kardo.inventory;

/** Que se le pide al operario que haga con el lote. */
public enum MarkdownAction {

    /** Bajar el precio al sugerido y ponerlo en el anaquel de ofertas. */
    REBAJAR,

    /** Ya vencio: fuera del anaquel y a declarar la merma. */
    RETIRAR,

    /**
     * El costo del lote no deja sitio para una rebaja. No es una tarea de
     * anaquel sino de quien fija los precios o negocia con el proveedor.
     */
    REVISAR_PRECIO
}

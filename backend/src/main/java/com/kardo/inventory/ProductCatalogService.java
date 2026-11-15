package com.kardo.inventory;

import com.kardo.dto.ProductCreateRequestDto;
import com.kardo.dto.ProductUpdateRequestDto;
import com.kardo.exception.DuplicateSkuException;
import com.kardo.exception.InvalidQuantityException;
import com.kardo.exception.ProductNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que se puede hacer con una ficha de producto: crearla y corregirla.
 *
 * <p><b>Por que no reutiliza la ingesta.</b> {@code InventoryIngestRepository}
 * inserta con {@code ON CONFLICT (company_id, sku) DO UPDATE}, y ahi es lo
 * correcto: un archivo del proveedor trae el catalogo entero y se espera que
 * actualice precios en bloque. Un alta manual es el gesto contrario -alguien
 * teclea un producto que cree nuevo- y pisar en silencio el precio de uno que ya
 * existia es un error caro que nadie ve hasta que la caja cobra de menos. Aqui
 * se comprueba antes y se rechaza con el nombre del que ya ocupa ese SKU, que es
 * lo unico que permite entender el choque.
 */
@Service
public class ProductCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogService.class);

    private static final String COMPANY_OF_STORE =
            "SELECT company_id FROM stores WHERE store_id = ?";

    private static final String EXISTING_SKU =
            "SELECT name FROM products WHERE company_id = ? AND sku = ?";

    private static final String INSERT_PRODUCT = """
            INSERT INTO products (company_id, sku, barcode, name, category,
                                  unit_of_measure, unit_cost, retail_price)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING product_id
            """;

    private static final String INSERT_BATCH = """
            INSERT INTO inventory_batches (store_id, product_id, batch_code, expiration_date,
                                           initial_qty, current_qty, unit_cost, batch_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            RETURNING batch_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProductCatalogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param storeId tienda del token; el producto cuelga de su compania, el
     *                lote de la tienda
     * @return el producto creado y, si lo hubo, su lote
     */
    @Transactional
    public Map<String, Object> create(long storeId, ProductCreateRequestDto request, String createdBy) {
        Long companyId = jdbcTemplate.queryForObject(COMPANY_OF_STORE, Long.class, storeId);

        String sku = request.sku().trim();
        try {
            String ocupado = jdbcTemplate.queryForObject(EXISTING_SKU, String.class, companyId, sku);
            throw new DuplicateSkuException(sku, ocupado);
        } catch (EmptyResultDataAccessException libre) {
            // Lo normal: el SKU no existe y se puede crear.
        }

        int cantidad = request.initialQty() == null ? 0 : request.initialQty();
        if (cantidad > 0 && request.expirationDate() == null) {
            // El FEFO ordena por vencimiento: un lote sin fecha seria invisible
            // al motor y nunca saldria a la venta, que es peor que no crearlo.
            throw new InvalidQuantityException(
                    "Un lote con existencias necesita fecha de vencimiento: es lo que ordena el FEFO.");
        }
        if (cantidad > 0 && request.expirationDate().isBefore(LocalDate.now())) {
            throw new InvalidQuantityException(
                    "La fecha de vencimiento ya paso: ese lote nacería vencido y no se podría vender.");
        }

        BigDecimal costo = request.unitCost() == null ? BigDecimal.ZERO : request.unitCost();
        BigDecimal precio = request.retailPrice() == null ? BigDecimal.ZERO : request.retailPrice();

        Long productId = jdbcTemplate.queryForObject(INSERT_PRODUCT, Long.class,
                companyId, sku, blankToNull(request.barcode()), request.name().trim(),
                request.category().trim().toUpperCase(),
                blankOr(request.unitOfMeasure(), "UNIDAD"), costo, precio);

        Long batchId = null;
        if (cantidad > 0) {
            batchId = jdbcTemplate.queryForObject(INSERT_BATCH, Long.class,
                    storeId, productId,
                    blankOr(request.batchCode(), "L-" + sku + "-" + LocalDate.now()),
                    request.expirationDate(), cantidad, cantidad, costo);
        }

        log.info("Alta de producto en la tienda {}: {} \"{}\" por {}{}",
                storeId, sku, request.name(), createdBy,
                batchId == null ? " (sin existencias)" : " con " + cantidad + " u");

        return Map.of(
                "productId", productId,
                "sku", sku,
                "name", request.name().trim(),
                "batchId", batchId == null ? "" : batchId,
                "initialQty", cantidad);
    }

    private static final String UPDATE_PRODUCT = """
            UPDATE products
               SET name            = ?,
                   category        = ?,
                   barcode         = COALESCE(?, barcode),
                   unit_of_measure = ?,
                   unit_cost       = ?,
                   retail_price    = ?
             WHERE company_id = ? AND sku = ?
            RETURNING product_id, name, category, unit_cost, retail_price
            """;

    /**
     * Corrige un producto que ya existe.
     *
     * <p><b>Lo que cambia y lo que no, que es la parte que hay que entender.</b>
     *
     * <p>El {@code retail_price} SI afecta a las ventas futuras, incluidas las
     * de los lotes que ya estan en el anaquel: {@code sp_process_fefo_sale} lee
     * el precio del catalogo salvo que el lote tenga rebaja propia. Subir el
     * precio aqui es subirlo en la caja a partir del siguiente cliente.
     *
     * <p>El {@code unit_cost} NO toca los lotes existentes. Cada
     * {@code inventory_batches} guarda el costo al que entro SU mercaderia, y
     * eso es lo que ancla el margen de lo ya recibido: si corregir la ficha
     * reescribiera el costo de lo comprado hace tres meses, el margen historico
     * cambiaria retroactivamente y el analisis dejaria de poder compararse
     * consigo mismo. El costo nuevo vale para lo que entre a partir de ahora.
     *
     * <p>Y las ventas ya registradas no se mueven en absoluto:
     * {@code daily_sales} congela precio y costo en cada fila.
     *
     * @return el producto corregido y cuantos lotes suyos conservan su costo
     */
    @Transactional
    public Map<String, Object> update(long storeId, String sku, ProductUpdateRequestDto request,
                                      String updatedBy) {
        Long companyId = jdbcTemplate.queryForObject(COMPANY_OF_STORE, Long.class, storeId);

        Map<String, Object> actualizado;
        try {
            actualizado = jdbcTemplate.queryForMap(UPDATE_PRODUCT,
                    request.name().trim(),
                    request.category().trim().toUpperCase(),
                    blankToNull(request.barcode()),
                    blankOr(request.unitOfMeasure(), "UNIDAD"),
                    request.unitCost(),
                    request.retailPrice(),
                    companyId, sku.trim());
        } catch (EmptyResultDataAccessException noExiste) {
            throw new ProductNotFoundException(sku);
        }

        Integer lotesConCostoPropio = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM inventory_batches
                 WHERE store_id = ? AND product_id = ? AND current_qty > 0
                """, Integer.class, storeId, actualizado.get("product_id"));

        log.info("Producto {} corregido en la tienda {} por {}: precio {}, costo {}",
                sku, storeId, updatedBy, request.retailPrice(), request.unitCost());

        return Map.of(
                "productId", actualizado.get("product_id"),
                "sku", sku.trim(),
                "name", actualizado.get("name"),
                "retailPrice", actualizado.get("retail_price"),
                "unitCost", actualizado.get("unit_cost"),
                // Para que la interfaz pueda avisar: el costo nuevo no los toca.
                "batchesKeepingTheirCost", lotesConCostoPropio);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

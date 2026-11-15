package com.kardo.web;

import com.kardo.dto.ProductCreateRequestDto;
import com.kardo.dto.ProductUpdateRequestDto;
import com.kardo.inventory.ProductCatalogService;
import com.kardo.repository.ProductCatalogRepository;
import com.kardo.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogo de la tienda.
 *
 * <p>Lo consume el selector de SKU de la pantalla de recepcion: cuando la IA no
 * mapea una linea, el operario elige el producto de esta lista.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductCatalogController {

    private final ProductCatalogRepository repository;
    private final ProductCatalogService catalogService;

    public ProductCatalogController(ProductCatalogRepository repository,
                                    ProductCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        // Cada usuario ve el catalogo de su propia compania, nunca el de otra.
        return ResponseEntity.ok(repository.listByStore(StoreScope.current()));
    }
    /**
     * Alta manual de un producto, con su primer lote si lo trae.
     *
     * <p>Lo pueden hacer operario y gerente, igual que la carga por Excel: esa
     * ya crea productos, de modo que exigir aqui un rol superior no cerraria
     * nada y solo obligaria al operario a dar el rodeo de armar un archivo.
     *
     * <p>Responde 409 si el SKU ya existe, en lugar de actualizarlo en silencio
     * como hace la ingesta masiva.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'MANAGER')")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody ProductCreateRequestDto request) {
        var user = CurrentUser.require();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalogService.create(user.storeId(), request, user.email()));
    }

    /**
     * Corrige un producto que ya existe.
     *
     * <p><b>Solo el gerente.</b> El resto del catalogo lo puede tocar el
     * operario -dar de alta un producto es meter datos- pero cambiar un precio
     * no es meter datos: es cambiar lo que cobra la caja en cada venta futura,
     * y a diferencia de una merma o un canje, aqui no hay nadie que lo resuelva
     * despues. Es la misma linea que separa declarar una merma de aprobarla.
     *
     * <p>El SKU no se corrige por aqui: es la clave con la que la ingesta por
     * Excel reconoce el producto, y cambiarlo partiria su historico en dos
     * fichas sin que nadie lo note.
     */
    @PutMapping(value = "/{sku}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String sku,
                                                      @Valid @RequestBody ProductUpdateRequestDto request) {
        var user = CurrentUser.require();
        return ResponseEntity.ok(catalogService.update(user.storeId(), sku, request, user.email()));
    }

}

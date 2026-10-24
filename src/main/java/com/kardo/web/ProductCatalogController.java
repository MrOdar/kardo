package com.kardo.web;

import com.kardo.repository.ProductCatalogRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    public ProductCatalogController(ProductCatalogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        // Cada usuario ve el catalogo de su propia compania, nunca el de otra.
        return ResponseEntity.ok(repository.listByStore(StoreScope.current()));
    }
}

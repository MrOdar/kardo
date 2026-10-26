package com.kardo.conciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kardo.config.MatchingProperties;
import com.kardo.dto.ConciliationLineDto;
import com.kardo.dto.ConfirmedLineDto;
import com.kardo.dto.ExtractedItemDto;
import com.kardo.dto.ExtractedReceiptDto;
import com.kardo.dto.ReceiptConfirmationRequestDto;
import com.kardo.dto.ReceiptConfirmationResponseDto;
import com.kardo.dto.ReceiptScanResponseDto;
import com.kardo.exception.InvalidReceiptLineException;
import com.kardo.repository.GoodsReceiptRepository;
import com.kardo.repository.SkuMatchingRepository;
import com.kardo.repository.StoreRepository;
import com.kardo.vision.GeminiVisionClient;
import com.kardo.vision.ImageProcessingService;
import com.kardo.vision.VisionQuotaService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conciliacion de guias de remision: lectura por vision y cruce contra el catalogo.
 *
 * <p>El flujo se parte en dos peticiones a proposito. {@code scan} solo lee
 * (imagen, IA y matching) y no escribe nada; {@code confirm} persiste lo que el
 * operario valido. Entre ambas no hay estado en el servidor: la pantalla de
 * correccion vive en el navegador y la confirmacion es autosuficiente.
 */
@Service
public class ReceiptConciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptConciliationService.class);

    /** El lote es NOT NULL en el DDL; una guia sin lote impreso recibe este marcador. */
    private static final String DEFAULT_BATCH_CODE = "SIN-LOTE";

    private final ImageProcessingService imageProcessingService;
    private final GeminiVisionClient geminiVisionClient;
    private final SkuMatchingRepository skuMatchingRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final StoreRepository storeRepository;
    private final VisionQuotaService visionQuotaService;
    private final MatchingProperties matchingProperties;
    private final ObjectMapper objectMapper;

    public ReceiptConciliationService(ImageProcessingService imageProcessingService,
                                      GeminiVisionClient geminiVisionClient,
                                      VisionQuotaService visionQuotaService,
                                      SkuMatchingRepository skuMatchingRepository,
                                      GoodsReceiptRepository goodsReceiptRepository,
                                      StoreRepository storeRepository,
                                      MatchingProperties matchingProperties,
                                      ObjectMapper objectMapper) {
        this.imageProcessingService = imageProcessingService;
        this.geminiVisionClient = geminiVisionClient;
        this.visionQuotaService = visionQuotaService;
        this.skuMatchingRepository = skuMatchingRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.storeRepository = storeRepository;
        this.matchingProperties = matchingProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Lee la guia y propone un SKU por linea.
     *
     * @param imageStream foto de la guia, leida directamente del multipart
     * @param storeId     tienda que recibe la mercaderia
     */
    public ReceiptScanResponseDto scan(InputStream imageStream, long storeId, String requestedBy) {
        long companyId = storeRepository.findCompanyIdByStore(storeId);

        // El preprocesado va antes de reservar cupo: una foto ilegible falla
        // aqui, sin haber llegado a Gemini, y cobrarsela al operario le gastaria
        // el dia por un error que el sistema detecto solo.
        String base64Jpeg = imageProcessingService.toBase64Jpeg(imageStream);

        var quota = visionQuotaService.reserve(storeId, requestedBy);

        var extraction = geminiVisionClient.extract(base64Jpeg);
        ExtractedReceiptDto receipt = extraction.receipt();

        List<ExtractedItemDto> items = receipt.items();
        List<ConciliationLineDto> lines = matchAgainstCatalog(companyId, items);
        int unmatched = (int) lines.stream().filter(line -> line.productId() == null).count();

        log.info("Guia {} leida con {}: {} lineas, {} sin mapear",
                receipt.documentNumber(), extraction.modelUsed(), lines.size(), unmatched);

        return new ReceiptScanResponseDto(
                receipt.supplierTaxId(),
                receipt.supplierName(),
                receipt.documentNumber(),
                receipt.issueDate(),
                extraction.modelUsed(),
                unmatched,
                lines,
                quota);
    }

    /**
     * Cruza las descripciones leidas contra el catalogo en un solo viaje de red.
     *
     * <p>Un score por debajo del umbral se trata como ausencia de match: es
     * preferible que el operario vincule el SKU a mano que imputar mercaderia al
     * producto equivocado y descuadrar el inventario.
     */
    private List<ConciliationLineDto> matchAgainstCatalog(long companyId, List<ExtractedItemDto> items) {
        if (items.isEmpty()) {
            return List.of();
        }

        List<Integer> lineIds = new ArrayList<>(items.size());
        List<String> ocrTexts = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            lineIds.add(index + 1);
            String description = items.get(index).descriptionRaw();
            ocrTexts.add(description == null ? "" : description);
        }

        Map<Integer, SkuMatchingRepository.SkuMatch> matchesByLine = new HashMap<>();
        for (SkuMatchingRepository.SkuMatch match : skuMatchingRepository.matchLines(companyId, lineIds, ocrTexts)) {
            matchesByLine.put(match.ocrLineId(), match);
        }

        List<ConciliationLineDto> lines = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            int ocrLineId = index + 1;
            ExtractedItemDto item = items.get(index);
            SkuMatchingRepository.SkuMatch match = matchesByLine.get(ocrLineId);

            boolean accepted = match != null
                    && match.productId() != null
                    && match.score() != null
                    && match.score() >= matchingProperties.minScore();

            lines.add(new ConciliationLineDto(
                    ocrLineId,
                    item.descriptionRaw(),
                    item.invoicedQty(),
                    item.unitCost(),
                    item.batchCode(),
                    item.expirationDate(),
                    accepted ? match.productId() : null,
                    accepted ? match.productName() : null,
                    match == null ? null : match.score()));
        }
        return lines;
    }

    /**
     * Persiste la recepcion confirmada por el operario.
     *
     * <p>Cabecera, lineas y lotes se escriben en una sola transaccion: si una
     * linea falla, no queda stock sin documento ni documento sin stock.
     */
    @Transactional
    public ReceiptConfirmationResponseDto confirm(ReceiptConfirmationRequestDto request,
                                                  String confirmedBy, String clientIp) {
        long storeId = request.storeId();
        long companyId = storeRepository.findCompanyIdByStore(storeId);

        long supplierId = goodsReceiptRepository.upsertSupplier(
                companyId, request.supplierName(), request.supplierTaxId());

        // Quien confirma queda en la cabecera: la conciliacion es una decision
        // humana sobre que entro de verdad al almacen, no un dato del documento.
        long receiptId = goodsReceiptRepository.insertHeader(
                storeId, supplierId, request.documentNumber(), request.issueDate(),
                toJsonOrNull(request), confirmedBy, clientIp);

        int batchesCreated = 0;
        List<Integer> discrepancies = new ArrayList<>();

        for (ConfirmedLineDto line : request.lines()) {
            validate(line);

            long itemId = goodsReceiptRepository.insertItem(receiptId, line);

            if (line.receivedQty().signum() > 0) {
                String batchCode = line.batchCode() == null || line.batchCode().isBlank()
                        ? DEFAULT_BATCH_CODE
                        : line.batchCode().trim();
                goodsReceiptRepository.insertBatch(storeId, itemId, line, batchCode);
                batchesCreated++;
            }

            if (line.receivedQty().compareTo(line.invoicedQty()) != 0) {
                discrepancies.add(line.ocrLineId());
            }
        }

        log.info("Guia {} confirmada en la tienda {}: {} lineas, {} lotes, {} discrepancias",
                request.documentNumber(), storeId, request.lines().size(), batchesCreated, discrepancies.size());

        return new ReceiptConfirmationResponseDto(
                receiptId, request.lines().size(), batchesCreated, discrepancies);
    }

    /**
     * Reglas que Bean Validation no puede expresar por si sola, porque dependen
     * de la combinacion de campos de la linea.
     */
    private static void validate(ConfirmedLineDto line) {
        if (line.receivedQty().signum() > 0) {
            if (line.expirationDate() == null) {
                throw new InvalidReceiptLineException(line.ocrLineId(),
                        "la fecha de vencimiento es obligatoria cuando se recibe mercaderia.");
            }
            if (line.manufacturingDate() != null
                    && line.expirationDate().isBefore(line.manufacturingDate())) {
                throw new InvalidReceiptLineException(line.ocrLineId(),
                        "la fecha de vencimiento no puede ser anterior a la de fabricacion.");
            }
            if (line.receivedQty().stripTrailingZeros().scale() > 0) {
                // inventory_batches.initial_qty es INT: media unidad no es un lote.
                throw new InvalidReceiptLineException(line.ocrLineId(),
                        "la cantidad recibida debe ser un numero entero de unidades.");
            }
        }
    }

    /** La respuesta cruda se archiva en {@code ocr_raw_json} para auditoria. */
    private String toJsonOrNull(ReceiptConfirmationRequestDto request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "confirmedLines", request.lines(),
                    "supplierTaxId", request.supplierTaxId(),
                    "documentNumber", request.documentNumber()));
        } catch (Exception ex) {
            log.warn("No se pudo serializar la conciliacion para auditoria: {}", ex.getMessage());
            return null;
        }
    }
}

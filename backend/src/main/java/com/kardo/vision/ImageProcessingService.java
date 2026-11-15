package com.kardo.vision;

import com.kardo.config.VisionProperties;
import com.kardo.exception.UnreadableImageException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prepara la foto de la guia de remision antes de enviarla a Gemini.
 *
 * <p>La resolucion es el parametro critico del OCR: por debajo de ~1024 px los
 * caracteres densos de una factura A4 se degradan y el modelo confunde 8 con 0 o
 * B con 6. Fijar el lado mayor en 1800 px con interpolacion bicubica y comprimir
 * al 85% mantiene legibles fuentes de 6 pt en un payload de unos 350 KB.
 *
 * <p>Nunca se amplia una imagen pequeña: interpolar pixeles que no existen no
 * añade informacion y solo infla el payload.
 */
@Service
public class ImageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingService.class);

    public static final String OUTPUT_MIME_TYPE = "image/jpeg";

    private final VisionProperties properties;

    public ImageProcessingService(VisionProperties properties) {
        this.properties = properties;
    }

    /**
     * @param inputStream imagen original (JPEG o PNG) tal como llego en el multipart
     * @return la imagen reescalada y comprimida, codificada en Base64 para el
     *         campo {@code inlineData} de la API de Gemini
     * @throws UnreadableImageException si el archivo no es una imagen decodificable
     */
    public String toBase64Jpeg(InputStream inputStream) {
        try {
            BufferedImage original = decodeWithinBudget(inputStream);

            BufferedImage prepared = resizeToMaxDimension(original);
            byte[] jpeg = compressToJpeg(prepared);

            log.debug("Imagen preparada: {}x{} -> {}x{}, {} KB",
                    original.getWidth(), original.getHeight(),
                    prepared.getWidth(), prepared.getHeight(), jpeg.length / 1024);

            return Base64.getEncoder().encodeToString(jpeg);

        } catch (IOException ex) {
            throw new UnreadableImageException("No se pudo procesar la imagen: " + ex.getMessage(), ex);
        }
    }

    /**
     * Decodifica la imagen solo despues de comprobar cuanto va a ocupar.
     *
     * <p><b>Por que no basta {@code ImageIO.read}.</b> Esa llamada decodifica
     * primero y pregunta despues, de modo que el tope de 10 MB del multipart no
     * protege de nada: la compresion sin perdida del PNG permite declarar
     * 30.000 x 30.000 px en unas decenas de KB, y materializar eso son 3,6 GB de
     * {@code int[]} que el contenedor de 512 MB no tiene. El proceso muere con
     * {@code OutOfMemoryError}, que no es una excepcion que se atrape ni se
     * traduzca a un 400: se cae la aplicacion entera, y con ella las ventas de
     * todas las tiendas. Es una denegacion de servicio con una foto.
     *
     * <p><b>El arreglo.</b> El lector de ImageIO sabe el ancho y el alto de la
     * cabecera sin descomprimir los pixeles. Se consulta primero y, si el
     * producto pasa del presupuesto, se rechaza con un 400 sin haber reservado
     * el buffer. La vista se cierra siempre, tambien al rechazar, para no dejar
     * el archivo temporal del multipart abierto.
     */
    private BufferedImage decodeWithinBudget(InputStream inputStream) throws IOException {
        try (var stream = ImageIO.createImageInputStream(inputStream)) {
            if (stream == null) {
                throw new UnreadableImageException(
                        "El archivo no es una imagen valida (se esperaba JPEG o PNG).");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new UnreadableImageException(
                        "El archivo no es una imagen valida (se esperaba JPEG o PNG).");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);

                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > properties.maxPixels()) {
                    log.warn("Imagen rechazada antes de decodificar: {}x{} = {} px, tope {}",
                            reader.getWidth(0), reader.getHeight(0), pixels, properties.maxPixels());
                    throw new UnreadableImageException(
                            "La imagen es demasiado grande: " + pixels / 1_000_000
                            + " megapixeles. El tope es " + properties.maxPixels() / 1_000_000 + ".");
                }

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new UnreadableImageException(
                            "El archivo no es una imagen valida (se esperaba JPEG o PNG).");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Reescala conservando la proporcion; devuelve la original si ya cabe. */
    BufferedImage resizeToMaxDimension(BufferedImage source) {
        int longestSide = Math.max(source.getWidth(), source.getHeight());
        if (longestSide <= properties.maxDimension()) {
            return toRgb(source);
        }

        double ratio = (double) properties.maxDimension() / longestSide;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        // TYPE_INT_RGB: el JPEG no tiene canal alfa y un PNG transparente
        // produciria fondos negros que arruinan el OCR.
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Aplana el canal alfa sobre blanco para que el JPEG no salga invertido. */
    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    byte[] compressToJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No hay un codificador JPEG disponible en esta JVM.");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);

            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(properties.jpegQuality());

            writer.write(null, new IIOImage(image, null, null), params);
            imageOutput.flush();
        } finally {
            writer.dispose();
        }

        return output.toByteArray();
    }
}

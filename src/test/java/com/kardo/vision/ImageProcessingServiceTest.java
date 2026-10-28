package com.kardo.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kardo.config.VisionProperties;
import com.kardo.exception.UnreadableImageException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageProcessingServiceTest {

    private final ImageProcessingService service =
            new ImageProcessingService(new VisionProperties(1800, 0.85f, 40_000_000L,
                    new VisionProperties.Quota(40, 200)));

    @Test
    @DisplayName("Fija el lado mayor en 1800 px conservando la proporcion")
    void reescalaAlLadoMayor() throws IOException {
        // Foto apaisada tipica de un celular
        String base64 = service.toBase64Jpeg(jpeg(4000, 3000));
        BufferedImage result = decode(base64);

        assertThat(result.getWidth()).isEqualTo(1800);
        // 3000 * (1800/4000) = 1350
        assertThat(result.getHeight()).isEqualTo(1350);
    }

    @Test
    @DisplayName("Reescala tambien cuando el lado mayor es la altura")
    void reescalaVertical() throws IOException {
        BufferedImage result = decode(service.toBase64Jpeg(jpeg(2400, 3200)));

        assertThat(result.getHeight()).isEqualTo(1800);
        assertThat(result.getWidth()).isEqualTo(1350);
    }

    @Test
    @DisplayName("No amplia una imagen que ya cabe en el limite")
    void noAmpliaImagenesPequenas() throws IOException {
        BufferedImage result = decode(service.toBase64Jpeg(jpeg(800, 600)));

        assertThat(result.getWidth()).isEqualTo(800);
        assertThat(result.getHeight()).isEqualTo(600);
    }

    @Test
    @DisplayName("El payload comprimido se mantiene en el orden de cientos de KB")
    void payloadAcotado() throws IOException {
        byte[] jpeg = Base64.getDecoder().decode(service.toBase64Jpeg(jpeg(4000, 3000)));

        assertThat(jpeg).isNotEmpty();
        // Margen amplio: lo que se verifica es que no se envie un bitmap crudo
        // de varios MB, no el tamanio exacto de una foto sintetica.
        assertThat(jpeg.length).isLessThan(2 * 1024 * 1024);
    }

    @Test
    @DisplayName("Un archivo que no es imagen se rechaza con un mensaje util")
    void archivoNoImagen() {
        InputStream basura = new ByteArrayInputStream("esto no es una imagen".getBytes());

        assertThatThrownBy(() -> service.toBase64Jpeg(basura))
                .isInstanceOf(UnreadableImageException.class)
                .hasMessageContaining("imagen");
    }

    @Test
    @DisplayName("Una imagen que no cabe en memoria se rechaza antes de decodificarla")
    void bombaDeDescompresion() throws IOException {
        // El tope real son 40 Mpx; aqui se baja a 1 Mpx para poder provocar el
        // rechazo con una imagen que esta prueba pueda generar. Lo que se
        // verifica es que la comprobacion ocurra, no la cifra.
        var acotado = new ImageProcessingService(
                new VisionProperties(1800, 0.85f, 1_000_000L, new VisionProperties.Quota(40, 200)));

        // 2000 x 2000 = 4 Mpx declarados en la cabecera. El ataque de verdad
        // son 30.000 x 30.000 en un PNG de 60 KB: 3,6 GB de buffer en un
        // contenedor de 512 MB, que no es una excepcion sino el proceso caido.
        assertThatThrownBy(() -> acotado.toBase64Jpeg(jpeg(2000, 2000)))
                .isInstanceOf(UnreadableImageException.class)
                .hasMessageContaining("megapixeles");

        // Y la misma imagen pasa sin problema con el tope de produccion: el
        // limite protege la disponibilidad, no estorba a una foto normal.
        assertThat(service.toBase64Jpeg(jpeg(2000, 2000))).isNotEmpty();
    }

    @Test
    @DisplayName("Un PNG con transparencia se aplana sobre blanco, no sobre negro")
    void aplanaTransparenciaSobreBlanco() throws IOException {
        BufferedImage transparente = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(transparente, "png", png);

        BufferedImage result = decode(service.toBase64Jpeg(new ByteArrayInputStream(png.toByteArray())));
        Color pixel = new Color(result.getRGB(50, 50));

        // Un fondo negro arruinaria el OCR de un documento escaneado
        assertThat(pixel.getRed()).isGreaterThan(240);
        assertThat(pixel.getGreen()).isGreaterThan(240);
        assertThat(pixel.getBlue()).isGreaterThan(240);
    }

    /** Imagen sintetica con texto y formas, para que la compresion tenga trabajo real. */
    private static InputStream jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        for (int y = 40; y < height - 40; y += 60) {
            graphics.drawString("GUIA DE REMISION 001-4567 CERV PILSEN 650 x 24", 40, y);
            graphics.drawLine(40, y + 10, width - 40, y + 10);
        }
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static BufferedImage decode(String base64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("el resultado debe ser un JPEG decodificable").isNotNull();
        return image;
    }
}

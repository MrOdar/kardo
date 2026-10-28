package com.kardo.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.kardo.config.SecurityConfig;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrato entre la interfaz y lo que la sostiene.
 *
 * <p><b>Que prueba y que no.</b> No comprueba que la pantalla se vea bien ni
 * que un boton haga lo que promete: eso necesitaria un navegador y seguiria
 * saltandose en la maquina de quien no lo tenga, que es el problema que ya
 * tenemos con Docker. Comprueba lo unico que en esta SPA se rompe <em>en
 * silencio</em>, porque son tres archivos que alguien mantiene sincronizados a
 * mano:
 *
 * <ul>
 *   <li>Un {@code $('id')} que apunta a un elemento que ya no esta en el HTML.
 *       En el navegador eso es un {@code null} que mata el resto del guion, sin
 *       que el servidor se entere de nada.</li>
 *   <li>Una llamada a una ruta que el backend ya no sirve, o que alguien
 *       renombro. La SPA recibe un 404 y la pantalla se queda a medias.</li>
 *   <li>Una pestaña añadida al HTML que nadie registro en el guion, o al reves:
 *       una pestaña muerta que no abre su panel.</li>
 * </ul>
 *
 * <p>Corre en cualquier maquina, sin Node, sin navegador y sin base de datos.
 */
class SpaContractTest {

    private static final Path HTML = Path.of("src/main/resources/static/index.html");
    private static final Path JS = Path.of("src/main/resources/static/app.js");

    /** Prefijo que el guion antepone a toda llamada (la constante {@code api}). */
    private static final String API_PREFIX = "/api/v1";

    private static String html;
    private static String js;

    @BeforeAll
    static void readSources() throws IOException {
        html = Files.readString(HTML, StandardCharsets.UTF_8);
        js = Files.readString(JS, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Todo elemento que el guion busca existe en el HTML")
    void ningunIdHuerfano() {
        Set<String> declarados = matches(html, "id=\"([a-zA-Z0-9_-]+)\"");
        Set<String> usados = matches(js, "\\$\\('([a-zA-Z0-9_-]+)'\\)");

        assertThat(usados)
                .describedAs("ids que app.js busca y no existen en index.html")
                .isSubsetOf(declarados);
    }

    /**
     * {@code getElementById} devuelve el primero y calla: un id repetido hace
     * que media interfaz escriba sobre el elemento equivocado.
     */
    @Test
    @DisplayName("Ningun id esta repetido en el HTML")
    void sinIdsRepetidos() {
        List<String> todos = allMatches(html, "id=\"([a-zA-Z0-9_-]+)\"");
        Set<String> unicos = new LinkedHashSet<>(todos);

        assertThat(todos).describedAs("ids duplicados en index.html").hasSameSizeAs(unicos);
    }

    @Test
    @DisplayName("Cada pestaña del HTML esta registrada en el guion y tiene su panel")
    void lasPestanasCuadran() {
        Set<String> enHtml = matches(html, "role=\"tab\" id=\"tab-([a-z]+)\"");
        Set<String> paneles = matches(html, "id=\"panel-([a-z]+)\" role=\"tabpanel\"");

        Matcher array = Pattern.compile("const TABS = \\[([^\\]]+)\\]").matcher(js);
        assertThat(array.find()).describedAs("el array TABS en app.js").isTrue();
        Set<String> enJs = matches(array.group(1), "'([a-z]+)'");

        assertThat(enJs).describedAs("pestañas declaradas en app.js").isEqualTo(enHtml);
        assertThat(paneles).describedAs("paneles de cada pestaña").isEqualTo(enHtml);
    }

    /**
     * La comprobacion que de verdad paga este archivo: que la interfaz y el
     * backend sigan de acuerdo sobre que URL existen.
     */
    @Test
    @DisplayName("Toda ruta que la interfaz llama la sirve algun controlador")
    void ningunaRutaInventada() {
        List<String> plantillas = backendRoutes();
        assertThat(plantillas).describedAs("rutas encontradas en los controladores").isNotEmpty();

        List<String> huerfanas = new ArrayList<>();
        for (String llamada : frontendRoutes()) {
            if (plantillas.stream().noneMatch(plantilla -> matchesTemplate(llamada, plantilla))) {
                huerfanas.add(llamada);
            }
        }

        assertThat(huerfanas)
                .describedAs("rutas que la SPA llama y ningun controlador sirve")
                .isEmpty();
    }

    /**
     * El hash de la CSP y el bloque que autoriza, sincronizados.
     *
     * <p>Es la contrapartida de haber quitado {@code 'unsafe-inline'} de
     * {@code script-src}. Si alguien toca el {@code tailwind.config} -cambiar un
     * color de la paleta basta-, el hash deja de cuadrar, el navegador se niega a
     * ejecutar el bloque y la interfaz sale sin estilos. Eso no se descubre en
     * una prueba de servidor ni en una revision: se descubre en produccion.
     * Aqui se recalcula desde el mismo archivo que se sirve, de modo que el
     * build cae antes.
     */
    @Test
    @DisplayName("El hash de la CSP corresponde al script en linea del HTML")
    void elHashDeLaCspCuadra() throws Exception {
        String esperado = "sha256-" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(inlineScript().getBytes(StandardCharsets.UTF_8)));

        assertThat(SecurityConfig.TAILWIND_CONFIG_HASH)
                .describedAs("""
                        El <script> en linea de index.html cambio y el hash de la CSP no.
                        Con el hash viejo el navegador bloquea ese bloque y la interfaz
                        sale sin estilos. Pon este valor en SecurityConfig.TAILWIND_CONFIG_HASH:
                        %s""".formatted(esperado))
                .isEqualTo(esperado);
    }

    /**
     * Un hash autoriza un bloque, no la practica de escribir codigo en el HTML.
     *
     * <p>Un segundo {@code <script>} en linea, o un {@code onclick=}, quedaria
     * bloqueado por la CSP y romperia esa parte de la interfaz en silencio. Y si
     * la respuesta fuera devolver {@code 'unsafe-inline'}, se habria desandado
     * el arreglo entero. Mejor que falle aqui.
     */
    @Test
    @DisplayName("Sigue habiendo un solo script en linea y ningun manejador onclick=")
    void nadaMasEnLinea() {
        assertThat(allMatches(html, "(?s)<script>(.*?)</script>"))
                .describedAs("bloques <script> sin src en index.html")
                .hasSize(1);

        assertThat(matches(html, "\\s(on[a-z]+)=\""))
                .describedAs("manejadores en linea; usa addEventListener en app.js")
                .isEmpty();
    }

    /** El contenido exacto del bloque, que es sobre lo que el navegador calcula el hash. */
    private static String inlineScript() {
        Matcher bloque = Pattern.compile("(?s)<script>(.*?)</script>").matcher(html);
        assertThat(bloque.find()).describedAs("el <script> en linea de index.html").isTrue();
        return bloque.group(1);
    }

    @Test
    @DisplayName("El contrato cubre las rutas que importan, no dos sueltas")
    void elContratoNoEstaVacio() {
        // Si un cambio de estilo rompiera la extraccion, las pruebas de arriba
        // pasarian con una lista vacia y nadie se enteraria.
        assertThat(frontendRoutes()).hasSizeGreaterThan(12);
        assertThat(matches(js, "\\$\\('([a-zA-Z0-9_-]+)'\\)")).hasSizeGreaterThan(40);
    }

    // ---------------------------------------------------------------------
    // Extraccion
    // ---------------------------------------------------------------------

    /**
     * Rutas que llama el guion.
     *
     * <p>Se recogen las dos formas que usa: {@code request('/ruta')} para el
     * JSON y {@code fetch(`${api}/ruta`)} para las descargas binarias.
     */
    private static List<String> frontendRoutes() {
        List<String> crudas = new ArrayList<>();
        crudas.addAll(allMatches(js, "request\\(\\s*[`']([^`']+)[`']"));
        crudas.addAll(allMatches(js, "\\$\\{api\\}([^`]*)"));

        return crudas.stream()
                // El propio `request()` arma la URL con la ruta que recibe; ese
                // punto no es una llamada sino el despachador.
                .filter(ruta -> !ruta.startsWith("${"))
                .flatMap(ruta -> expand(ruta).stream())
                .map(SpaContractTest::normalize)
                .filter(ruta -> ruta.startsWith(API_PREFIX))
                .distinct()
                .toList();
    }

    /**
     * Desdobla un sufijo elegido con un condicional.
     *
     * <p>La descarga usa {@code `${api}/inventory/export${pdf ? '/pdf' : ''}`}:
     * son dos rutas distintas, y tratarlas como un comodin dejaria sin
     * comprobar justo la que se añadio ultima.
     */
    private static List<String> expand(String raw) {
        Matcher condicional = Pattern
                .compile("\\$\\{[^}]*\\?\\s*'([^']*)'\\s*:\\s*'([^']*)'\\s*\\}")
                .matcher(raw);

        if (!condicional.find()) {
            return List.of(raw);
        }
        String antes = raw.substring(0, condicional.start());
        String despues = raw.substring(condicional.end());
        return List.of(antes + condicional.group(1) + despues,
                       antes + condicional.group(2) + despues);
    }

    /**
     * Deja la ruta comparable: sin query, con el prefijo y con las
     * interpolaciones convertidas en comodines.
     */
    private static String normalize(String raw) {
        String path = raw.replaceAll("\\$\\{[^}]*\\}", "{}");
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.startsWith(API_PREFIX)) {
            path = API_PREFIX + path;
        }
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    /** Plantillas declaradas por los controladores, leidas por reflexion. */
    private static List<String> backendRoutes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> rutas = new ArrayList<>();
        for (BeanDefinition definicion : scanner.findCandidateComponents("com.kardo.web")) {
            Class<?> controlador = load(definicion.getBeanClassName());
            for (String base : paths(AnnotatedElementUtils.findMergedAnnotation(
                    controlador, RequestMapping.class))) {
                for (Method metodo : controlador.getDeclaredMethods()) {
                    RequestMapping mapeo =
                            AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                    if (mapeo == null) {
                        continue;
                    }
                    for (String sufijo : paths(mapeo)) {
                        rutas.add(join(base, sufijo));
                    }
                }
            }
        }
        return rutas;
    }

    private static String[] paths(RequestMapping mapeo) {
        if (mapeo == null) {
            return new String[]{""};
        }
        String[] declaradas = mapeo.path().length > 0 ? mapeo.path() : mapeo.value();
        return declaradas.length > 0 ? declaradas : new String[]{""};
    }

    private static String join(String base, String sufijo) {
        String ruta = (base + "/" + sufijo).replaceAll("/{2,}", "/");
        return ruta.endsWith("/") && ruta.length() > 1 ? ruta.substring(0, ruta.length() - 1) : ruta;
    }

    /**
     * Una llamada encaja con una plantilla si tienen los mismos segmentos y
     * cada uno coincide o es variable.
     *
     * <p>Las interpolaciones del guion son comodines por los dos lados: en
     * {@code /waste/${id}/${decision}} el verbo tambien viene de una variable,
     * y debe poder encajar con {@code /waste/{wasteId}/approve}.
     */
    private static boolean matchesTemplate(String llamada, String plantilla) {
        String[] izquierda = llamada.split("/");
        String[] derecha = plantilla.split("/");
        if (izquierda.length != derecha.length) {
            return false;
        }
        for (int i = 0; i < izquierda.length; i++) {
            if (!variable(izquierda[i]) && !variable(derecha[i])
                    && !izquierda[i].equals(derecha[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Un segmento es variable si lleva una interpolacion en cualquier parte.
     *
     * <p>No basta con reconocer {@code {wasteId}}: el guion tambien produce
     * segmentos mixtos como {@code waste{}} cuando pega la query a la ruta, y
     * compararlos literalmente seria pedirle al texto algo que no sabe.
     */
    private static boolean variable(String segmento) {
        return segmento.contains("{");
    }

    private static Class<?> load(String nombre) {
        try {
            return Class.forName(nombre);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Controlador no cargable: " + nombre, ex);
        }
    }

    private static Set<String> matches(String source, String regex) {
        return new LinkedHashSet<>(allMatches(source, regex));
    }

    private static List<String> allMatches(String source, String regex) {
        List<String> encontrados = new ArrayList<>();
        Matcher matcher = Pattern.compile(regex).matcher(source);
        while (matcher.find()) {
            encontrados.add(matcher.group(1));
        }
        return encontrados;
    }
}

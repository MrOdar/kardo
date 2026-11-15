package com.kardo.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Lo que sale por la respuesta cuando algo falla, y sobre todo lo que no sale.
 *
 * <p><b>Por que existe este archivo.</b> {@code GlobalExceptionHandler} son
 * 255 lineas que deciden el codigo y el cuerpo de todos los errores del
 * producto, y hasta ahora ninguna prueba lo nombraba. Dos cosas quedaban sin
 * red: que el {@code @ExceptionHandler(Exception.class)} del final no se coma
 * los errores de protocolo de Spring -un 405 devuelto como 500- y que ningun
 * manejador deje escapar al cliente el interior del servidor. Las dos fallan
 * en silencio: la respuesta sigue llegando, con el codigo o el texto
 * equivocado, y nada se rompe hasta que alguien depende de ello.
 *
 * <p><b>Por que con el despachador real y no llamando a los metodos.</b>
 * Invocar el manejador a mano comprobaria la aritmetica de cada rama pero no lo
 * que de verdad puede romperse: que Spring elija el manejador <em>mas
 * especifico</em> y que las excepciones que el propio despachador lanza
 * -metodo no permitido, ruta inexistente, parametro que falta- lleguen hasta
 * aqui. Eso es seleccion de manejadores, no codigo nuestro, y solo se ve
 * pasando una peticion de verdad.
 *
 * <p>El controlador de abajo existe solo para esta prueba: necesita rutas que
 * fallen a proposito, y usar las del producto ataria estas pruebas a los
 * servicios que hay detras.
 *
 * <p><b>Por que no {@code @WebMvcTest}.</b> La porcion web arrastra el filtro
 * JWT y, con el, media aplicacion: habria que simular los servicios de todos
 * los controladores para probar una clase que no depende de ninguno.
 * {@code standaloneSetup} monta el mismo {@code DispatcherServlet} con la
 * misma seleccion de manejadores y sin contexto de Spring, que es lo que hace
 * falta aqui.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SondaController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    // -----------------------------------------------------------------------
    // Lo que pidio esta prueba: que el catch-all no se trague el protocolo
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Errores de protocolo")
    class ErroresDeProtocolo {

        /**
         * El fallo concreto que esto vigila: {@code HttpRequestMethodNotSupportedException}
         * es una {@code Exception} como cualquier otra, de modo que sin la rama
         * de {@code ErrorResponse} el catch-all la convertiria en un 500 y el
         * cliente creeria que el servidor esta roto cuando lo que esta mal es su
         * peticion.
         */
        @Test
        @DisplayName("Un metodo no permitido sigue siendo 405 y no un 500")
        void metodoNoPermitido() throws Exception {
            mockMvc.perform(delete("/sonda/ok"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.error").value("Method Not Allowed"));
        }

        /**
         * Lo que de verdad lanza el despachador en produccion ante una ruta que
         * no existe: la sirve el manejador de recursos estaticos, no el de
         * controladores, y acaba en un {@code NoResourceFoundException} que
         * tambien es {@code ErrorResponse}. Comprobado contra el despliegue:
         * una ruta inventada responde 404 con este cuerpo.
         */
        @Test
        @DisplayName("Un recurso inexistente sigue siendo 404 y no un 500")
        void recursoInexistente() throws Exception {
            mockMvc.perform(get("/sonda/recurso-que-no-esta"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Un parametro obligatorio que falta es 400, no 500")
        void parametroQueFalta() throws Exception {
            mockMvc.perform(get("/sonda/con-parametro"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        /**
         * Esta prueba encontro el fallo: {@code TypeMismatchException} no
         * implementa {@code ErrorResponse}, asi que caia en la red de seguridad
         * y {@code /inventory/waste/abc/approve} respondia 500 en produccion.
         * Nombra el parametro, para que el cliente sepa cual corregir, y no
         * devuelve el valor que mando.
         */
        @Test
        @DisplayName("Un id que no es un numero es 400, no 500")
        void tipoQueNoCuadra() throws Exception {
            mockMvc.perform(get("/sonda/numero/no-soy-un-numero"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message", containsString("id")))
                    .andExpect(content().string(not(containsString("no-soy-un-numero"))));
        }

        /**
         * {@code HttpMessageNotReadableException} no implementa
         * {@code ErrorResponse}, asi que cae en el catch-all por su propia rama.
         * Su mensaje trae el fragmento del cuerpo que no se pudo parsear, y ese
         * fragmento es del cliente: devolverselo seria inofensivo, pero el
         * mensaje tambien nombra la clase del parser y la posicion del error.
         */
        @Test
        @DisplayName("Un JSON roto es 400, y el cuerpo ilegible no vuelve en la respuesta")
        void jsonRoto() throws Exception {
            mockMvc.perform(post("/sonda/cuerpo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nombre\": \"sin cerrar"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("El cuerpo de la peticion no es un JSON valido."))
                    .andExpect(content().string(not(containsString("sin cerrar"))))
                    .andExpect(content().string(not(containsString("JsonParseException"))));
        }
    }

    // -----------------------------------------------------------------------
    // La razon de ser del catch-all
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("No se filtra el interior")
    class NoSeFiltraElInterior {

        /**
         * Sin el catch-all, el manejador por defecto de Spring Boot responde
         * con {@code exception}, {@code message} y a veces {@code trace}: el
         * nombre de la clase que fallo y, con suerte para quien busca, la pila.
         */
        @Test
        @DisplayName("Un fallo no contemplado es 500 con una frase, sin clase ni pila")
        void falloNoContemplado() throws Exception {
            mockMvc.perform(get("/sonda/npe"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message")
                            .value("Ocurrio un error inesperado. Si vuelve a pasar, avisa a soporte."))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist())
                    .andExpect(content().string(not(containsString("NullPointerException"))))
                    .andExpect(content().string(not(containsString("el anaquel estaba vacio"))));
        }

        /**
         * El caso que mas se parece a una fuga real: el mensaje de un
         * {@code DataAccessException} arrastra el SQL con los nombres de las
         * tablas y columnas, que es un mapa del esquema.
         */
        @Test
        @DisplayName("Un fallo de base de datos no devuelve el SQL ni los nombres de las tablas")
        void falloDeBaseDeDatos() throws Exception {
            mockMvc.perform(get("/sonda/sql"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(not(containsString("inventory_batches"))))
                    .andExpect(content().string(not(containsString("SELECT"))));
        }

        @Test
        @DisplayName("Una violacion de unicidad es 409 sin el nombre de la restriccion")
        void violacionDeUnicidad() throws Exception {
            mockMvc.perform(get("/sonda/duplicado"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("El documento ya fue registrado para esta tienda."))
                    .andExpect(content().string(not(containsString("uq_grn_store"))));
        }

        /**
         * Decirle al cliente que tienda existe o que rol le falta le da
         * material para seguir probando, de modo que el 403 es siempre la misma
         * frase sea cual sea el motivo.
         */
        @Test
        @DisplayName("Un 403 no dice que tienda era ni que rol faltaba")
        void prohibidoSinDetalle() throws Exception {
            mockMvc.perform(get("/sonda/tienda-ajena"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("No tienes permiso para realizar esta operacion."))
                    // Sobre el mensaje y no sobre el cuerpo entero: el id de
                    // tienda es un numero corto y aparece por casualidad en los
                    // nanosegundos del timestamp.
                    .andExpect(jsonPath("$.message", not(containsString("77"))))
                    .andExpect(content().string(not(containsString("sesion de la tienda"))));
        }

        @Test
        @DisplayName("Un AccessDeniedException da el mismo 403, con la misma frase")
        void accesoDenegadoPorRol() throws Exception {
            mockMvc.perform(get("/sonda/sin-rol"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("No tienes permiso para realizar esta operacion."))
                    .andExpect(content().string(not(containsString("ROLE_MANAGER"))));
        }

        @Test
        @DisplayName("Un fallo de la IA es 502 sin lo que respondio el modelo")
        void falloDeLaIa() throws Exception {
            mockMvc.perform(get("/sonda/gemini"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.message")
                            .value("No se pudo interpretar el documento. Reintenta con una foto mas nitida."))
                    .andExpect(content().string(not(containsString("candidates"))));
        }
    }

    // -----------------------------------------------------------------------
    // El contraste: donde el mensaje si es para el cliente
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Lo que si se le dice al cliente")
    class LoQueSiSeDice {

        /**
         * El contrapunto de {@link NoSeFiltraElInterior}: aqui el mensaje si
         * viaja, porque lo escribimos nosotros para que alguien lo lea.
         */
        @Test
        @DisplayName("Un error de negocio devuelve su propio mensaje")
        void errorDeNegocio() throws Exception {
            mockMvc.perform(get("/sonda/sin-stock"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("Solo quedan 3 unidades del lote L-2026-01."));
        }

        /**
         * 422 significa "esto esta mal"; aqui el archivo esta bien y lo que no
         * cabe es el trabajo. Con un 413 el cliente sabe que la salida es
         * dividir el archivo, no corregirlo.
         */
        @Test
        @DisplayName("Un archivo con demasiadas filas es 413 y no 422")
        void demasiadasFilas() throws Exception {
            mockMvc.perform(get("/sonda/archivo-largo"))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.message", containsString("5000")));
        }

        /**
         * Sin {@code Retry-After} el cliente no tiene forma de saber cuanto
         * esperar, y se pone a sondear: exactamente lo que el freno pretende
         * evitar.
         */
        @Test
        @DisplayName("Un freno por intentos lleva Retry-After en segundos")
        void frenoConRetryAfter() throws Exception {
            mockMvc.perform(get("/sonda/freno"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "90"));
        }

        /** Un solo mensaje no basta: hay que decir que lotes bloquean. */
        @Test
        @DisplayName("Una reversion bloqueada enumera los lotes que la impiden")
        void reversionBloqueada() throws Exception {
            mockMvc.perform(get("/sonda/no-revertible"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.details.length()").value(2))
                    .andExpect(jsonPath("$.details[0]").value("L-2026-01: 4 unidades vendidas"))
                    .andExpect(jsonPath("$.details[1]").value("L-2026-02: merma aprobada"));
        }

        @Test
        @DisplayName("Un payload invalido nombra el campo y el motivo")
        void payloadInvalido() throws Exception {
            mockMvc.perform(post("/sonda/validado")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nombre\": \"\", \"cantidad\": 0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("nombre")))
                    .andExpect(jsonPath("$.message", containsString("cantidad")));
        }

        /**
         * El cuerpo de error es contrato con el SPA, que lo lee en todas las
         * pantallas. Un campo que cambie de nombre lo rompe en silencio.
         */
        @Test
        @DisplayName("Todo error trae timestamp, status, error y message")
        void formaDelCuerpo() throws Exception {
            mockMvc.perform(get("/sonda/sin-stock"))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").exists())
                    // details va con NON_EMPTY: si no hay detalle, no hay campo
                    // vacio que el SPA tenga que distinguir de uno ausente.
                    .andExpect(jsonPath("$.details").doesNotExist());
        }
    }

    // -----------------------------------------------------------------------
    // La regla de orden en la que se apoya todo lo anterior
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("El catch-all no tapa a los demas")
    class ElCatchAllNoTapa {

        /**
         * {@code @ExceptionHandler(Exception.class)} abarca por tipo a todas las
         * excepciones del producto. Que no se las quede depende de que Spring
         * elija siempre el manejador mas especifico; el comentario del archivo
         * lo afirma y esta prueba lo comprueba, porque es la suposicion sobre la
         * que descansa el resto de esta clase.
         */
        @Test
        @DisplayName("Una excepcion con manejador propio no acaba en el de 500")
        void gananLosEspecificos() throws Exception {
            mockMvc.perform(get("/sonda/sin-stock"))
                    .andExpect(status().isConflict());

            mockMvc.perform(get("/sonda/gemini"))
                    .andExpect(status().isBadGateway());

            mockMvc.perform(get("/sonda/tienda-ajena"))
                    .andExpect(status().isForbidden());
        }
    }

    // -----------------------------------------------------------------------
    // Rutas que fallan a proposito
    // -----------------------------------------------------------------------

    /**
     * Existe solo para esta prueba. Cada ruta lanza una excepcion concreta para
     * que la peticion recorra el despachador real hasta el manejador, que es la
     * parte que no se puede comprobar llamando a los metodos a mano.
     */
    @RestController
    @RequestMapping("/sonda")
    @Validated
    static class SondaController {

        @GetMapping("/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/con-parametro")
        String conParametro(@RequestParam String obligatorio) {
            return obligatorio;
        }

        @GetMapping("/numero/{id}")
        String numero(@PathVariable long id) {
            return String.valueOf(id);
        }

        @PostMapping("/cuerpo")
        String cuerpo(@RequestBody Payload payload) {
            return payload.nombre();
        }

        @PostMapping("/validado")
        String validado(@Valid @RequestBody PayloadValidado payload) {
            return payload.nombre();
        }

        @GetMapping("/recurso-que-no-esta")
        String recursoQueNoEsta() throws NoResourceFoundException {
            // Spring 7 anadio un tercer argumento: la ruta tal como llego, aparte
            // de la del recurso. Aqui son la misma.
            throw new NoResourceFoundException(HttpMethod.GET,
                    "/sonda/recurso-que-no-esta", "/sonda/recurso-que-no-esta");
        }

        @GetMapping("/npe")
        String npe() {
            throw new NullPointerException("el anaquel estaba vacio");
        }

        @GetMapping("/sql")
        String sql() {
            throw new DataAccessResourceFailureException(
                    "ERROR: relation does not exist; SELECT batch_id FROM inventory_batches WHERE store_id = 1");
        }

        @GetMapping("/duplicado")
        String duplicado() {
            throw new DuplicateKeyException(
                    "duplicate key value violates unique constraint \"uq_grn_store\"");
        }

        @GetMapping("/tienda-ajena")
        String tiendaAjena() {
            throw new ForbiddenStoreException(77L, 1L);
        }

        @GetMapping("/sin-rol")
        String sinRol() {
            throw new AccessDeniedException("Se requiere ROLE_MANAGER para aprobar mermas");
        }

        @GetMapping("/gemini")
        String gemini() {
            throw new GeminiExtractionException(
                    "respuesta inesperada del modelo: {\"candidates\": []}");
        }

        @GetMapping("/sin-stock")
        String sinStock() {
            throw new InsufficientStockException("Solo quedan 3 unidades del lote L-2026-01.");
        }

        @GetMapping("/archivo-largo")
        String archivoLargo() {
            throw new SpreadsheetTooLargeException(5000);
        }

        @GetMapping("/freno")
        String freno() {
            throw new TooManyAttemptsException("Demasiados intentos.", Duration.ofSeconds(90));
        }

        @GetMapping("/no-revertible")
        String noRevertible() {
            throw new UploadNotRevertibleException("La carga tiene movimiento.", List.of(
                    "L-2026-01: 4 unidades vendidas",
                    "L-2026-02: merma aprobada"));
        }

        record Payload(String nombre) {
        }

        record PayloadValidado(@NotBlank String nombre, @Min(1) int cantidad) {
        }
    }
}

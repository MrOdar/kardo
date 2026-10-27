/*
 * Las dos fronteras de la SPA con el servidor.
 *
 * `request()` es el unico sitio por donde la pagina habla con el backend, y
 * `escapeHtml()` el unico por donde los datos del backend entran al DOM. Lo que
 * se fija aqui es el otro extremo del trabajo hecho en
 * `GlobalExceptionHandlerTest`: alli se comprueba que el servidor manda un
 * `message` util y no su interior; aqui, que la pagina lo ensene en vez de
 * tragarselo, y que lo que venga de la base no pueda ejecutarse.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { abrirPagina } from './arnes.js';

let pagina;

afterEach(() => pagina?.cerrar());

describe('request()', () => {

  /**
   * El contrato con `ApiError`: el campo que el operario tiene que leer es
   * `message`. Si la pagina cayera al `status statusText`, un 409 con una
   * explicacion -"Solo quedan 3 unidades del lote L-2026-01"- se veria como
   * "409 Conflict", que no dice que hacer.
   */
  it('un error del servidor llega con el mensaje del servidor', async () => {
    pagina = abrirPagina((path) => path === '/ventas'
      ? { status: 409, statusText: 'Conflict',
          body: { timestamp: '2026-10-23T00:00:00Z', status: 409, error: 'Conflict',
                  message: 'Solo quedan 3 unidades del lote L-2026-01.' } }
      : undefined);

    await expect(pagina.evaluar("request('/ventas')"))
      .rejects.toThrow('Solo quedan 3 unidades del lote L-2026-01.');
  });

  /**
   * Un 502 de un proxy, o un 413 de nginx antes de llegar a Spring, devuelven
   * HTML. Sin la vuelta atras, `JSON.parse` lanzaria y el operario veria un
   * error de sintaxis en lugar del codigo.
   */
  it('un cuerpo que no es JSON no rompe: queda el codigo', async () => {
    pagina = abrirPagina((path) => path === '/ventas'
      ? { status: 502, statusText: 'Bad Gateway', body: '<html>502 Bad Gateway</html>' }
      : undefined);

    await expect(pagina.evaluar("request('/ventas')"))
      .rejects.toThrow('502 Bad Gateway');
  });

  it('una respuesta vacia no se confunde con un fallo', async () => {
    pagina = abrirPagina((path) => path === '/demo/reset'
      ? { status: 204, body: '' }
      : undefined);

    await expect(pagina.evaluar("request('/demo/reset')")).resolves.toBeNull();
  });

  it('el token del estado viaja en la cabecera, y sin token no se inventa', async () => {
    pagina = abrirPagina();

    await pagina.evaluar("request('/inventory/batches')");
    expect(pagina.llamadas.at(-1).options.headers.Authorization).toBeUndefined();

    pagina.evaluar("state.token = 'jwt-de-prueba'");
    await pagina.evaluar("request('/inventory/batches')");
    expect(pagina.llamadas.at(-1).options.headers.Authorization)
      .toBe('Bearer jwt-de-prueba');
  });
});

describe('escapeHtml()', () => {

  /**
   * La pagina arma casi todas sus tablas con `innerHTML`, y lo que mete viene
   * del catalogo: nombres de producto y de lote que alguien teclea en un Excel
   * y que la ingesta acepta como texto. Es la via de entrada mas plausible que
   * tiene este producto para un script ajeno, y no la cierra la CSP sola: un
   * `<img onerror>` inyectado en el DOM es un manejador en linea, no un script
   * externo.
   */
  it('un nombre con etiquetas no se convierte en un elemento', () => {
    pagina = abrirPagina();

    const escapado = pagina.evaluar(
      `escapeHtml('<img src=x onerror="alert(1)">')`);

    expect(escapado).not.toContain('<img');
    expect(escapado).toContain('&lt;img');

    // La prueba de verdad: puesto en el DOM no crea nada ejecutable.
    const caja = pagina.document.createElement('div');
    caja.innerHTML = escapado;
    expect(caja.querySelector('img')).toBeNull();
    expect(caja.textContent).toContain('onerror');
  });

  it('escapa tambien las comillas, que es lo que cierra un atributo', () => {
    pagina = abrirPagina();

    // Sin esto, un nombre puesto dentro de class="..." o title="..." podria
    // salirse del atributo y abrir uno nuevo.
    expect(pagina.evaluar(`escapeHtml('a" onmouseover="alert(1)')`))
      .toBe('a&quot; onmouseover=&quot;alert(1)');
    expect(pagina.evaluar(`escapeHtml("a' onfocus='x")`))
      .toBe('a&#39; onfocus=&#39;x');
  });

  /**
   * Un lote sin fecha, un proveedor sin nombre: el backend manda `null` y la
   * celda tiene que quedar vacia. Con el `String(value)` pelado saldria la
   * palabra "null" impresa en la tabla.
   */
  it('null y undefined quedan en blanco, no en la palabra "null"', () => {
    pagina = abrirPagina();

    expect(pagina.evaluar('escapeHtml(null)')).toBe('');
    expect(pagina.evaluar('escapeHtml(undefined)')).toBe('');
    // Un cero si es un dato: no se cae por el mismo agujero.
    expect(pagina.evaluar('escapeHtml(0)')).toBe('0');
  });
});

describe('sembrar la demo', () => {

  /**
   * Si falla, el motivo se queda en pantalla.
   *
   * <p>Salia como aviso flotante de ocho segundos. Es el unico gesto de la
   * pantalla sin sesion y la primera vez que alguien toca el producto: cuando
   * el aviso se va solo, el visitante queda delante de una pagina que no
   * cambio y sin nada que leer. Y el fallo mas probable no es raro -el
   * contenedor de la capa gratuita duerme y tarda en despertar-, de modo que
   * le pasa justo a quien llega por primera vez.
   */
  it('un fallo al sembrar deja el motivo en pantalla, no un aviso que se va', async () => {
    pagina = abrirPagina((path) => path === '/demo/reset'
      ? { status: 503, statusText: 'Service Unavailable',
          body: { timestamp: '2026-10-27T00:00:00Z', status: 503, error: 'Service Unavailable',
                  message: 'El servicio de base de datos no responde.' } }
      : undefined);

    const { evaluar, $ } = pagina;
    await evaluar('resetDemo()');

    expect($('demo-error').classList.contains('hidden'),
      'el bloque de error sigue oculto tras fallar').toBe(false);
    expect($('demo-error').getAttribute('role')).toBe('alert');
    expect($('demo-error-detail').textContent)
      .toBe('El servicio de base de datos no responde.');

    // Los tres disparadores vuelven a estar disponibles: un fallo que deja el
    // boton girando obliga a recargar para reintentar.
    for (const id of ['btn-demo', 'btn-demo-home', 'btn-demo-mobile']) {
      expect($(id).disabled, `${id} quedo bloqueado tras el fallo`).toBe(false);
    }
  });

  /**
   * El reintento retira el error anterior antes de salir a la red: dejarlo
   * puesto mientras el boton gira hace dudar de si lo que se lee es de ahora
   * o de hace un minuto.
   */
  it('reintentar retira el error anterior', async () => {
    pagina = abrirPagina((path) => path === '/demo/reset'
      ? { status: 503, body: { message: 'No responde.' } }
      : undefined);

    const { evaluar, $ } = pagina;
    await evaluar('resetDemo()');
    expect($('demo-error').classList.contains('hidden')).toBe(false);

    // El estado que se mira es el intermedio: `resetDemo` retira el aviso
    // antes de su primer `await`, asi que se comprueba con la promesa todavia
    // en vuelo. Luego se espera igualmente, o quedaria pendiente cuando la
    // prueba cierre la ventana y el fallo saldria como un rechazo sin dueno.
    const enVuelo = evaluar('resetDemo()');
    expect($('demo-error').classList.contains('hidden'),
      'el error del intento anterior sigue puesto durante el reintento').toBe(true);
    await enVuelo;
  });
});

describe('navegacion por pestañas', () => {

  /**
   * Las pestañas ocultan paneles en vez de recrearlos, porque el estado de la
   * cuarentena -las filas que el operario esta corrigiendo a mano- vive en el
   * DOM. Si cambiar de pestaña lo recreara, se perderia el trabajo sin aviso.
   */
  it('cambiar de pestaña no destruye lo que hay en los otros paneles', () => {
    pagina = abrirPagina();
    const { evaluar, $, conSesion } = pagina;

    // Las secciones estan cerradas mientras no haya sesion, asi que navegar
    // entre ellas exige una: sin esto showTab se queda en inicio y la prueba
    // mediria el portero, no la conservacion de los paneles.
    conSesion();

    evaluar("showTab('excel')");
    const panelExcel = $('panel-excel');
    panelExcel.dataset.marcaDePrueba = 'no me recrees';

    evaluar("showTab('analisis')");
    expect($('panel-excel').dataset.marcaDePrueba).toBe('no me recrees');
    expect($('panel-excel').classList.contains('hidden')).toBe(true);
    expect($('panel-analisis').classList.contains('hidden')).toBe(false);

    evaluar("showTab('excel')");
    expect($('panel-excel').dataset.marcaDePrueba).toBe('no me recrees');
    expect($('panel-excel').classList.contains('hidden')).toBe(false);
  });

  /**
   * El boton de "Registrar una venta" de la pestaña Ventas no abre un
   * formulario propio: lleva al que ya existe en el semaforo, junto a la cola
   * de salida. Lo que se fija aqui es que el viaje termine util -en el selector
   * de producto, listo para escribir- y no dejando al usuario en otra pestaña
   * buscando donde estaba el formulario. Con raton se nota a medias; con
   * teclado, el foco se quedaria en un boton que ya no se ve.
   */
  it('registrar una venta lleva al formulario y deja el foco dentro', () => {
    pagina = abrirPagina();
    const { evaluar, $, document, conSesion } = pagina;
    conSesion();

    evaluar("showTab('ventas')");
    $('btn-nueva-venta').click();

    expect($('panel-semaforo').classList.contains('hidden')).toBe(false);
    expect($('panel-ventas').classList.contains('hidden')).toBe(true);
    expect(document.activeElement).toBe($('sale-sku'));
  });

  /**
   * Sin sesion, las ocho secciones que necesitan datos estan cerradas.
   *
   * <p>Estaban abiertas, y llevaban a pantallas en cero: `S/ 0.00`,
   * `Lotes activos 0`, un formulario de venta con el boton habilitado. Quien
   * viene a juzgar el proyecto explora antes de pulsar el boton de la demo, y
   * concluia en quince segundos que el producto esta vacio.
   *
   * <p>Se comprueba sobre `showTab` y no sobre el clic porque `showTab` es el
   * portero: por ahi pasan el raton, las flechas del teclado y los saltos de
   * una seccion a otra. Una prueba sobre el clic dejaria sin cubrir las otras
   * dos puertas.
   */
  it('sin sesion solo se abre inicio, y el resto avisa de que falta un paso', () => {
    pagina = abrirPagina();
    const { evaluar, $ } = pagina;

    for (const nombre of evaluar('TABS').filter((t) => t !== 'inicio')) {
      evaluar(`showTab('${nombre}')`);
      expect($(`panel-${nombre}`).classList.contains('hidden'),
        `${nombre} se abrio sin sesion`).toBe(true);
      expect($(`tab-${nombre}`).getAttribute('aria-disabled'),
        `${nombre} no se anuncia como no disponible`).toBe('true');
    }

    expect($('panel-inicio').classList.contains('hidden')).toBe(false);

    // Con sesion se abren las mismas ocho, y el anuncio desaparece: el cierre
    // es un estado, no una puerta tapiada.
    pagina.conSesion();
    evaluar('renderToday()');
    evaluar("showTab('semaforo')");

    expect($('panel-semaforo').classList.contains('hidden')).toBe(false);
    expect($('tab-semaforo').getAttribute('aria-disabled')).toBeNull();
  });

  it('no hay dos formularios de venta: el de registrar es uno solo', () => {
    pagina = abrirPagina();

    // Si alguien duplica el formulario en la pestaña Ventas, esto lo delata:
    // serian dos implementaciones del mismo gesto, que se desincronizan.
    expect(pagina.document.querySelectorAll('#sale-form')).toHaveLength(1);
    expect(pagina.document.querySelectorAll('[id$="sale-qty"]')).toHaveLength(1);
  });

  it('cada pestaña declarada tiene su panel en el HTML', () => {
    pagina = abrirPagina();

    // SpaContractTest ya lo comprueba leyendo los archivos; aqui se comprueba
    // ejecutandolo, que es lo que falla si showTab cambia de convencion de
    // nombres sin que el HTML cambie.
    pagina.conSesion();

    const pestanas = pagina.evaluar('TABS');
    for (const nombre of pestanas) {
      pagina.evaluar(`showTab('${nombre}')`);
      expect(pagina.$(`panel-${nombre}`),
        `falta el panel de ${nombre}`).not.toBeNull();
      expect(pagina.$(`panel-${nombre}`).classList.contains('hidden'),
        `${nombre} no se mostro`).toBe(false);
    }
  });
});

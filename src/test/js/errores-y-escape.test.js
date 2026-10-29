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

describe('navegacion por pestañas', () => {

  /**
   * Las pestañas ocultan paneles en vez de recrearlos, porque el estado de la
   * cuarentena -las filas que el operario esta corrigiendo a mano- vive en el
   * DOM. Si cambiar de pestaña lo recreara, se perderia el trabajo sin aviso.
   */
  it('cambiar de pestaña no destruye lo que hay en los otros paneles', () => {
    pagina = abrirPagina();
    const { evaluar, $ } = pagina;

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

  it('cada pestaña declarada tiene su panel en el HTML', () => {
    pagina = abrirPagina();

    // SpaContractTest ya lo comprueba leyendo los archivos; aqui se comprueba
    // ejecutandolo, que es lo que falla si showTab cambia de convencion de
    // nombres sin que el HTML cambie.
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

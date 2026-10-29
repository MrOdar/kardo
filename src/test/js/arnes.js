/*
 * Arnes para probar la SPA tal como se sirve.
 *
 * Por que existe asi. `app.js` es un script clasico de 2.800 lineas sin
 * modulos: no se puede importar, y convertirlo en modulos para poder probarlo
 * cambiaria el artefacto que se despliega para beneficio de las pruebas, que es
 * justo al reves de como debe ser. Aqui se carga el `index.html` de verdad en
 * jsdom y se le inyecta el `app.js` de verdad como un <script>, que es lo que
 * hace el navegador. Las declaraciones `const` del nivel superior de un script
 * clasico quedan en el ambito lexico global, de modo que desde la prueba se
 * llega a ellas con `window.eval`.
 *
 * Lo unico que se sustituye es `fetch`: es el unico limite entre la pagina y el
 * servidor -todo pasa por `request()`- asi que falsearlo ahi deja intacto el
 * resto del guion.
 */
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';

const RAIZ = new URL('../../main/resources/static/', import.meta.url);

const html = readFileSync(new URL('index.html', RAIZ), 'utf8');
const guion = readFileSync(new URL('app.js', RAIZ), 'utf8');

/**
 * Levanta la pagina con el guion ya ejecutado.
 *
 * @param respuestas function(path, options) -> {status, body} | undefined.
 *        Lo que no conteste cae en un 200 con cuerpo vacio: la mayoria de las
 *        pruebas solo le importa una llamada y no tiene por que declarar las
 *        otras cinco que hace el arranque.
 */
export function abrirPagina(respuestas = () => undefined) {
  const llamadas = [];

  const dom = new JSDOM(html, {
    url: 'http://localhost/',
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    beforeParse(window) {
      // `index.html` lleva un bloque en linea que configura Tailwind, y el CDN
      // que define `tailwind` no se carga aqui. Se le da el hueco desde fuera
      // en vez de tocar el HTML: ese bloque es exactamente el que la CSP
      // autoriza por su hash SHA-256, asi que cambiarlo para las pruebas
      // romperia la pagina en produccion.
      window.tailwind = { config: {} };
    }
  });

  const { window } = dom;

  window.fetch = async (url, options = {}) => {
    const path = String(url).replace(/^\/api\/v1/, '');
    llamadas.push({ path, options });

    const respuesta = respuestas(path, options) ?? { status: 200, body: '' };
    const cuerpo = typeof respuesta.body === 'string'
      ? respuesta.body
      : JSON.stringify(respuesta.body ?? '');

    return {
      ok: respuesta.status >= 200 && respuesta.status < 300,
      status: respuesta.status,
      statusText: respuesta.statusText ?? '',
      text: async () => cuerpo,
      json: async () => JSON.parse(cuerpo),
      blob: async () => ({ size: cuerpo.length }),
      headers: { get: () => null }
    };
  };

  // El arranque de app.js llama a pingBackend() y deja un setInterval de un
  // minuto. En una prueba no interesa ninguno de los dos, y el temporizador
  // mantendria el proceso vivo.
  const temporizadores = [];
  const setIntervalReal = window.setInterval;
  window.setInterval = (fn, ms) => {
    const id = setIntervalReal.call(window, fn, ms);
    temporizadores.push(id);
    return id;
  };

  const script = window.document.createElement('script');
  script.textContent = guion;
  window.document.body.appendChild(script);

  return {
    window,
    document: window.document,
    llamadas,
    /**
     * Lee o escribe el estado interno del guion.
     *
     * Tiene que ser por `eval` y no por `window.algo`: las declaraciones
     * `const` del nivel superior de un script van al ambito lexico global, que
     * no es una propiedad de `window` -eso solo vale para `var`-. Es la misma
     * razon por la que `state` no se puede tocar desde fuera del realm.
     */
    evaluar: (expresion) => window.eval(expresion),
    /**
     * Mete datos en `state` desde la prueba.
     *
     * Serializa y deserializa dentro del realm a proposito: un `Set` o un array
     * creados aqui pertenecen al realm de la prueba y no son los del guion, de
     * modo que `instanceof` y los metodos no coinciden. Pasando por JSON los
     * objetos nacen dentro.
     */
    sembrar: (campos) => window.eval(`Object.assign(state, ${JSON.stringify(campos)})`),
    $: (id) => window.document.getElementById(id),
    cerrar: () => {
      temporizadores.forEach((id) => window.clearInterval(id));
      window.close();
    }
  };
}

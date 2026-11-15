/*
 * Servidor estático de desarrollo.
 *
 * Desde que el front se despliega aparte, hace falta algo que sirva esta
 * carpeta en un origen distinto al del backend: es la única manera de probar
 * en local lo mismo que pasa en producción, CORS incluido. Servirlo desde el
 * jar lo haría mismo-origen y el CORS no se ejercitaría nunca hasta desplegar.
 *
 * Son treinta líneas y no una dependencia a propósito. El proyecto no instala
 * Node para construirse -Render no lo tiene- y meter un paquete de servidor
 * por un uso de desarrollo desequilibra esa decisión.
 *
 *   node dev-server.mjs            sirve en http://localhost:5173
 *   node dev-server.mjs 4000       en otro puerto
 *
 * El puerto por defecto es 5173 porque es el que el backend ya autoriza en
 * kardo.security.cors.allowed-origins. Cambiarlo exige cambiar también eso.
 *
 * No es para producción: no cachea, no comprime y no sirve HTTPS.
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const RAIZ = fileURLToPath(new URL('.', import.meta.url));
const PUERTO = Number(process.argv[2]) || 5173;

const TIPOS = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
};

createServer(async (peticion, respuesta) => {
  const url = new URL(peticion.url, `http://localhost:${PUERTO}`);

  // normalize() colapsa los `..`, de modo que una petición a /../../etc/passwd
  // no se sale de esta carpeta. Es un servidor de desarrollo, pero el agujero
  // de recorrido de rutas no cuesta nada cerrarlo.
  let ruta = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '');

  // Ojo con el separador: en Windows normalize('/app/') devuelve '\app\', de
  // modo que buscar solo '/' deja la carpeta sin su index.html y responde 404.
  if (/[/\\]$/.test(ruta)) ruta = join(ruta, 'index.html');

  const archivo = join(RAIZ, ruta);
  if (!archivo.startsWith(RAIZ)) {
    respuesta.writeHead(403).end('Fuera de la carpeta servida');
    return;
  }

  try {
    const cuerpo = await readFile(archivo);
    respuesta.writeHead(200, {
      'content-type': TIPOS[extname(archivo).toLowerCase()] ?? 'application/octet-stream',
      'cache-control': 'no-store'
    }).end(cuerpo);
  } catch {
    respuesta.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
      .end(`No existe ${ruta}\n\nLa landing va en index.html y la aplicación en app/index.html.`);
  }
}).listen(PUERTO, () => {
  console.log(`Front servido en http://localhost:${PUERTO}`);
  console.log(`  landing      http://localhost:${PUERTO}/`);
  console.log(`  aplicación   http://localhost:${PUERTO}/app/`);
  console.log('La API tiene que estar en http://localhost:8080 (cd backend && mvnw spring-boot:run -Dspring-boot.run.profiles=local)');
});

/**
 * Tema de Kardo.
 *
 * Vivia en un <script> en linea dentro de index.html, que era lo que obligaba a
 * autorizar ese bloque por su hash en la CSP y a cargar el compilador de
 * Tailwind en el navegador de cada operario. Ahora el CSS se compila una vez
 * aqui y se sirve desde el jar: la pagina ya no depende de que cdn.tailwindcss.com
 * conteste, que era la unica razon por la que la interfaz podia salir entera sin
 * estilos.
 *
 * La version esta clavada a 3.4.17, la misma que servia el CDN, para que el
 * cambio sea un intercambio fiel y no un salto de version disfrazado de mejora.
 */
export default {
  // app.js escribe clases en plantillas de texto; ninguna se construye por
  // concatenacion, asi que el escaner las encuentra todas leyendo el archivo.
  content: ['./src/main/resources/static/index.html', './src/main/resources/static/app.js'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Instrument Sans"', 'system-ui', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'monospace']
      },
      colors: {
        ink:   { DEFAULT: '#0B1220', soft: '#3D4A63', faint: '#5C6881' },
        // El azul se mueve a un registro pastel: el mismo papel —estructura y
        // accion— con menos voltaje, de modo que el rojo de un vencimiento
        // siga siendo lo mas fuerte de la pantalla.
        brand: { DEFAULT: '#3F6FD0', deep: '#2F4F86', soft: '#DBE6FA', tint: '#EEF3FD' },
        canvas: '#F3F6FC',
        surface: '#FFFFFF',
        line:  { DEFAULT: '#E2E8F4', strong: '#CBD6EA' },
        risk:  {
          red: '#B3261E', amber: '#8A5A00', green: '#116A45',
          'red-tint': '#FDECEA', 'amber-tint': '#FBF0DC', 'green-tint': '#E6F4ED'
        }
      },
      boxShadow: {
        card: '0 1px 2px rgba(11,18,32,.04), 0 8px 24px -12px rgba(11,18,32,.14)',
        lift: '0 2px 4px rgba(11,18,32,.06), 0 16px 32px -16px rgba(11,18,32,.22)'
      }
    }
  }
};

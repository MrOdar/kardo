/*
 * Lo poco que la landing necesita de JavaScript.
 *
 * La página se lee entera sin esto: el texto está en el HTML y los reveals son
 * `animation-timeline: view()`, que no necesita guion. Esto cubre las dos
 * cosas que el CSS no puede hacer solo —contar una cifra y suplir
 * `animation-timeline` donde el navegador aún no lo trae— y nada más.
 *
 * Si alguien pide menos movimiento, no se anima nada: ni reveals, ni cifras.
 * La página queda completa y quieta, que es lo que ha pedido.
 */
const quieto = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/* ─────────────────── reveals ───────────────────
   Con IntersectionObserver y no con `animation-timeline: view()`. Atado al
   scroll, el rango se completa en cuanto el bloque entra holgado en pantalla:
   cuando el ojo llega, la animación ya terminó y no se percibe ninguna. El
   observador la dispara una vez, entera, en el momento en que el bloque asoma.

   Marcar <html> antes de pintar y no después evita el parpadeo: las reglas de
   .js-reveal ponen los bloques a opacidad cero, y si llegaran tarde se verían
   aparecer ya visibles y luego desvanecerse. */
if (!quieto) {
  document.documentElement.classList.add('js-reveal');

  const observador = new IntersectionObserver((entradas) => {
    for (const entrada of entradas) {
      if (!entrada.isIntersecting) continue;
      const nodo = entrada.target;
      nodo.classList.add('visible');
      // Una vez revelado, fuera: el bloque no vuelve a esconderse al subir, y
      // seguir observándolo solo gasta trabajo en cada scroll.
      observador.unobserve(nodo);

      // Terminada la entrada se retira la animación. Un `transform` residual
      // -aunque sea la identidad- deja al elemento en su propia capa de
      // composición y le quita al texto el antialiasing subpixel: la pagina
      // entera se ve ligeramente sucia sin que nada parezca estar mal.
      const limpiar = () => nodo.classList.add('hecho');
      const ultimo = nodo.classList.contains('reveal-stagger')
        ? nodo.children[nodo.children.length - 1]
        : nodo;
      ultimo.addEventListener('animationend', limpiar, { once: true });
      // Red de seguridad: si la animacion no llega a correr -pestaña en
      // segundo plano al entrar- el evento no se emite y el bloque se
      // quedaria invisible para siempre.
      setTimeout(limpiar, 2000);
    }
  }, { rootMargin: '0px 0px -10% 0px', threshold: 0.15 });

  document.querySelectorAll('.reveal').forEach((nodo) => observador.observe(nodo));
}

/* ─────────────────── cifras que cuentan ───────────────────
   El "Number Ticker" del banco. Cuenta al entrar en pantalla y no al cargar:
   una cifra que ya terminó de contarse antes de que llegues no la ve nadie. */
function contar(nodo) {
  const hasta = Number(nodo.dataset.hasta);
  const decimales = Number(nodo.dataset.decimales ?? 0);
  if (!Number.isFinite(hasta)) return;

  const DURACION = 1100;
  const arranque = performance.now();

  function paso(ahora) {
    const avance = Math.min((ahora - arranque) / DURACION, 1);
    // Salida exponencial, la misma curva que usa el resto del sistema: arranca
    // rápido y se posa, en vez de frenar en seco sobre la cifra final.
    const suave = 1 - Math.pow(1 - avance, 3);
    nodo.textContent = (hasta * suave).toFixed(decimales);
    if (avance < 1) requestAnimationFrame(paso);
  }
  requestAnimationFrame(paso);
}

const cifras = document.querySelectorAll('.ticker[data-hasta]');

if (quieto) {
  // Sin animación, pero con el dato: dejar el 0 del HTML sería mentir.
  cifras.forEach((nodo) => {
    nodo.textContent = Number(nodo.dataset.hasta)
      .toFixed(Number(nodo.dataset.decimales ?? 0));
  });
} else {
  const miron = new IntersectionObserver((entradas) => {
    for (const entrada of entradas) {
      if (!entrada.isIntersecting) continue;
      contar(entrada.target);
      miron.unobserve(entrada.target);
    }
  }, { threshold: 0.6 });

  cifras.forEach((nodo) => miron.observe(nodo));
}

/* ─────────────────── la escalera de rebaja ───────────────────
   El peldaño que te queda enfrente se enciende conforme bajas. Es el único
   efecto de esta página que no sale del banco, y el que lleva el argumento:
   el precio baja de peldaño en peldaño y se para en el costo del lote.

   Se apaga con prefers-reduced-motion porque es decorativo: los cuatro
   peldaños y sus precios se leen igual sin que ninguno se encienda. */
const escalera = document.querySelector('[data-escalera]');

if (escalera && !quieto) {
  const peldanos = [...escalera.querySelectorAll('.peldano')];

  const foco = new IntersectionObserver((entradas) => {
    for (const entrada of entradas) {
      // El piso tiene su propio color -es rojo de riesgo, no azul de marca- y
      // no se le toca: encenderlo como a los demás borraría justo la
      // diferencia que hay que ver.
      if (entrada.target.dataset.piso === 'si') continue;
      entrada.target.dataset.activo = entrada.isIntersecting ? 'si' : 'no';
    }
  }, { threshold: 0.85 });

  peldanos.forEach((nodo) => foco.observe(nodo));
}

/* ─────────────────── la consola jugable ───────────────────
 * Las secciones de la barra cambian el panel de verdad. Es una simulación y no
 * un vídeo: el visitante elige qué mirar, que es lo que hará dentro.
 *
 * Va sola de una sección a otra cada cinco segundos, y para en cuanto alguien
 * toca una. Esa parada no es un detalle: una demostración que sigue girando
 * mientras lees te quita la pantalla de debajo justo cuando te interesó algo.
 */
const simulacion = document.querySelector('[data-simulacion]');

if (simulacion) {
  const pestanas = [...simulacion.querySelectorAll('[role="tab"]')];
  const panel = (p) => document.getElementById(p.getAttribute('aria-controls'));

  function mostrar(elegida) {
    for (const p of pestanas) {
      const activa = p === elegida;
      p.setAttribute('aria-selected', String(activa));
      p.classList.toggle('nav-item-activo', activa);
      panel(p).hidden = !activa;
    }
  }

  let solo = null;
  function detenerSolo() {
    if (solo === null) return;
    clearInterval(solo);
    solo = null;
  }

  pestanas.forEach((p) => p.addEventListener('click', () => {
    detenerSolo();
    mostrar(p);
  }));

  // Flechas entre secciones, como en la aplicación de verdad.
  simulacion.addEventListener('keydown', (evento) => {
    const paso = evento.key === 'ArrowDown' ? 1 : evento.key === 'ArrowUp' ? -1 : 0;
    if (!paso) return;
    evento.preventDefault();
    detenerSolo();
    const actual = pestanas.findIndex((p) => p.getAttribute('aria-selected') === 'true');
    const siguiente = pestanas[(actual + paso + pestanas.length) % pestanas.length];
    mostrar(siguiente);
    siguiente.focus();
  });

  if (!quieto) {
    // Empieza a girar cuando la maqueta esta a la vista, no al cargar: si no,
    // el visitante llega a la cuarta pantalla sin haber visto las tres
    // primeras.
    new IntersectionObserver((entradas, obs) => {
      if (!entradas.some((e) => e.isIntersecting)) return;
      obs.disconnect();
      solo = setInterval(() => {
        const actual = pestanas.findIndex((p) => p.getAttribute('aria-selected') === 'true');
        mostrar(pestanas[(actual + 1) % pestanas.length]);
      }, 5000);
    }, { threshold: 0.4 }).observe(simulacion.closest('.reveal') ?? simulacion);
  }
}

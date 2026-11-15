---
target: landing page (estado deslogueado)
total_score: 16
max_score: 32
na_heuristics: 7,10
p0_count: 1
p1_count: 2
target_identity: "file:C:\\Users\\USUARIO\\OneDrive\\Desktop\\odar\\odar\\kardo\\src\\main\\resources\\static\\index.html"
target_fingerprint: "sha256:597da6312c1aa99a6c9f43e6d88578028ac1fe577cd039d66437b0b4d9a3918a"
target_path: "C:\\Users\\USUARIO\\OneDrive\\Desktop\\odar\\odar\\kardo\\src\\main\\resources\\static\\index.html"
timestamp: 2026-10-27T16-26-12Z
slug: src-main-resources-static-index-html
---
Method: dual-agent (A: revisión de diseño · B: detector + navegador)

# Crítica — landing (estado deslogueado) de Kardo

Target: vista deslogueada de src/main/resources/static/index.html, servida en http://localhost:8080.

## Design Health Score

| # | Heurística | Punt. | Problema clave |
|---|-----------|-------|----------------|
| 1 | Visibilidad del estado | 2 | Deslogueado no dice que no hay sesión: `S/ 0.00` y `Lotes activos 0` se presentan como datos reales |
| 2 | Correspondencia con el mundo real | 3 | `<title>` en inglés bajo `<html lang="es">`; «Actualizar» no significa nada sin datos |
| 3 | Control y libertad | 1 | `resetDemo()` hace `POST /demo/reset` sin confirmación ni deshacer, sobre tienda compartida |
| 4 | Consistencia y estándares | 2 | «Restaurar tienda demo» x3 vs «Restaurar demo» x1; «Iniciar sesión» vs «Entrar»; 12 px fuera de la escala declarada |
| 5 | Prevención de errores | 1 | 9 pestañas y «Registrar salida» habilitados sin sesión (verificado: disabled:false) |
| 6 | Reconocer antes que recordar | 3 | Nombra «gerente y operario» y nunca muestra esas credenciales en la landing |
| 7 | Flexibilidad y eficiencia | n/a | Superficie Persuade de un solo camino; no hay modo experto que acelerar |
| 8 | Estético y minimalista | 2 | 372 px (41 %) de lienzo vacío en escritorio; 13 controles para una sola decisión |
| 9 | Recuperación de errores | 2 | Fallo como toast efímero con el mensaje crudo del backend |
| 10 | Ayuda y documentación | n/a | Un solo paso; «Qué hay detrás» cubre la necesidad informativa |
| **Total** | | **16/32** | **Por debajo de la media** |

Heurísticas n/a: 7 y 10. Máximo aplicable: 32.

## Veredicto de especificidad

La landing no es una landing: es el empty state del dashboard (`#today-onboarding`, comentado
«Primera visita: sin datos») con la consola Operate entera alrededor. El `<h1>` más grande dice
«Lo primero de hoy» con «MARTES, 27 DE OCTUBRE» encima; el argumento de venta va un nivel por
debajo, a 20 px, dentro de una tarjeta. Cambia el nombre, el logotipo y los nueve rótulos del nav
y la pantalla sirve sin tocar nada para cualquier SaaS B2B.

Cero cifras: 957 caracteres de copy en `#panel-inicio`, `S/` aparece 0 veces. Ningún precio, lote,
día restante ni captura. El posicionamiento declarado es «otros pintan un semáforo; nosotros
llegamos al precio» y la única pantalla que verá la mayoría no enseña un precio.

Sí es de este producto: el logotipo de tres barras, la agrupación «Decidir hoy / Operar / Revisar»,
el vocabulario (lote, FEFO, guía de remisión) y «un precio sugerido que nunca baja del costo».

### Escaneo determinista

22 hallazgos, exit 2, todos en index.html: cramped-padding x11, low-contrast x9, overused-font x1,
em-dash-overuse x1.

Falsos positivos verificados contra el DOM renderizado: los 9 de contraste y el de em-dashes son
falsos para esta vista. El markup tiene 41 input/textarea/select y 0 visibles deslogueado; cada
placeholder señalado vive bajo un ancestro display:none. Igual los 41 em-dashes, en atributos y
paneles ocultos. La medición en vivo da 0 fallos de contraste sobre 16 pares (escritorio) y 13
(móvil); mínimo real 4.78:1. Los 11 cramped-padding no son verificables: el detector los identifica
sólo por clase de margen, sin selector ni línea.

### Overlays visuales

No hay. La inyección de detect.js quedó bloqueada por la propia CSP del proyecto
(script-src 'self', sin unsafe-inline ni nonce), así que no se arrancó el live-server. No es un
defecto del sitio.

### Discrepancia A/B aclarada

B midió «0 px vacíos bajo el último contenido» y A midió 372 px. Ambas correctas: el último
elemento es el contenedor flex (900 px); el último contenido con tinta acaba en y=528. Los 372 px
son el número que importa.

## Impresión general

Lo que está construido debajo es serio y la página no lo demuestra. Hay un producto con motor FEFO
transaccional, ingesta con cuarentena y segregación de funciones, y el visitante se lleva una
agenda vacía y un botón. La mayor oportunidad: enseñar una fila real en vez de prometerla.

## Lo que funciona

1. La escalera 1-2-3 dice el mecanismo, no el beneficio. «El motor FEFO descuenta del lote que
   caduca primero y cobra su precio rebajado» es una frase que un competidor no puede escribir sin
   haberlo construido. Reutiliza el componente «Fila de prioridad» en vez de inventar uno de landing.
2. Las superficies del navegador están tematizadas de verdad: ::selection, caret-color,
   scrollbar-color, :focus-visible 2 px con offset. Verificado tabulando con teclas reales: foco
   visible en todos los controles, el orden cicla, sin trampa de foco.
3. El contraste es medido, no declarado: 0 fallos AA sobre 16 pares. El rótulo de grupo del
   sidebar, lo más flojo, da 4.93:1.

## Problemas prioritarios

### [P0] «Restaurar tienda demo» destruye una tienda compartida sin avisar ni preguntar

resetDemo() dispara POST /demo/reset directo — verificado: ni confirm, ni diálogo, ni showModal en
la función. La demo es compartida (PRODUCT.md: «Una sola tienda demo compartida»), hay tres
disparadores del mismo borrado, y en móvil el botón destructivo es azul sólido, primario, a un dedo
del primer pixel. Dos evaluadores simultáneos se pisan y ninguno entiende por qué sus datos
cambiaron. «Restaurar» además miente por omisión: suena a recuperación y es DELETE+INSERT.

Arreglo: línea fija bajo el CTA a 13 px ink.faint — «Siembra de cero la tienda demo compartida.
Borra lo que cualquier otra prueba haya dejado». Confirmación sólo cuando ya existe sesión, para que
el primer clic de un visitante virgen no tenga fricción. Unificar las cuatro etiquetas.

Comando: /impeccable harden

### [P1] La landing es el dashboard vacío, y el nav deslogueado sirve pantallas muertas

Verificado en vivo: 9 pestañas, ninguna deshabilitada, y «Registrar salida» con disabled:false sin
sesión. Pulsar «Semáforo FEFO» devuelve VALOR EN RIESGO —, S/ 0.00, Lotes activos 0. Explorar antes
de pulsar el botón es el comportamiento por defecto de quien juzga un proyecto, y es justo lo que
destruye la impresión. DESIGN.md condena de la versión anterior las «métricas en guiones»: siguen
ahí, a un clic.

Arreglo: sin sesión, suprimir #today-date, #btn-today-refresh y el h1 «Lo primero de hoy», y
promover «Una tienda demo, sembrada en un clic» a .section-title. Poner los 8 ítems restantes en
aria-disabled="true" tabindex="-1" con title explicativo. disabled en todo control de escritura
mientras state.token sea nulo.

Comando: /impeccable onboard

### [P1] Cero evidencia: la tesis se afirma y no se muestra

Ningún precio, lote, día restante, captura ni chip de riesgo. Una fila congelada y real —SKU, lote,
«2 d», costo S/ 3.10, precio vigente S/ 5.90, sugerido S/ 4.20, con el piso marcado— es menos código
que la escalera que ya existe y demuestra de un vistazo lo que ahora hay que creer por fe. El
componente de tabla y los chips ya están hechos.

Comando: /impeccable bolder

### [P2] En móvil el CTA nace bajo el pliegue y la prueba técnica queda invisible

Medido a 375x812: #btn-demo-home en y=871, 59 px por debajo del pliegue. «Qué hay detrás» ocupa
y=943-1249, íntegramente invisible y en último lugar. El cromo consume 162 px (20 % del viewport)
antes de la primera palabra. «Actualizar» es el primer elemento interactivo del contenido y es un
no-op. Los 14 controles miden menos de 44 px de alto en ambos tamaños; los peores son los dos que
más se pulsan: «Restaurar demo» 28 px y «Entrar» 30 px. El nav esconde 961 px a la derecha sin
flecha ni degradado de corte.

Comando: /impeccable adapt

### [P2] Violaciones de DESIGN.md verificables abriendo el repo

- font-weight: 700 en .section-title (kardo.css:121) y en 3 sitios más, contra «nunca 700 salvo en
  la cifra principal de un indicador». No hay ninguna cifra en la página.
- 12 px en 5 elementos, contra la escala declarada 28/20/16/14/13/11. El peso 500 declarado no
  aparece en ninguna parte.
- #connection-dot usa bg-risk-green para «conectado»: rompe «el color de riesgo sólo donde hay
  riesgo» y «el estado nunca se comunica solo por color» — 8x8 px, sin texto ni aria-label.
- title «Kardo — Smart Inventory & FEFO Engine» en inglés, verificado.
- Las fuentes siguen colgando de fonts.googleapis.com. DESIGN.md dedica un párrafo a haber sacado
  Tailwind del CDN porque el aspecto dependía de un tercero; la identidad tipográfica sigue ahí. Y
  IBM Plex Mono se descarga y no se usa en esta pantalla.

Comando: /impeccable polish

## Banderas rojas por persona

Evaluador técnico, 90 s, sin credenciales: gasta los primeros segundos entendiendo que «Lo primero
de hoy / MARTES, 27 DE OCTUBRE» no es su agenda. Los 9 ítems del nav lo invitan a explorar y
explorar lo hunde. No le da tiempo a pulsar el botón, esperar el arranque en frío de un contenedor
de 512 MB y navegar. Al cerrar: 372 px de vacío, sin enlace al repositorio, sin autoría, sin stack
visible salvo «Capa gratuita · Render + Supabase» a 12 px en el rincón del sidebar.

Gerente de tienda, en el teléfono: el CTA no existe para quien no hace scroll. Lo primero que puede
tocar es «Actualizar», y no pasa nada. Los objetivos táctiles que necesita fallan los 44 px. El
botón más destructivo es el más fácil de pulsar por accidente. No hay una sola frase en su idioma de
negocio: no lee «una lata que vence en 3 días se baja a S/ 4.20 y la caja cobra eso», lee «bloqueo
pesimista» e «idempotencia por huella», 943 px más abajo.

## Observaciones menores

- El paso 1 de la escalera duplica el CTA: «1. Restaura la tienda demo» y, 170 px abajo, el botón
  que hace eso. La escalera gana empezando en el paso 2.
- Escritorio sin scroll (scrollHeight 900 = viewport): la barra de desplazamiento tematizada de la
  que presume DESIGN.md no se ve nunca en la única pantalla que verá la mayoría.
- #session y #store-switcher están en el DOM con placeholders «—» aunque ocultos: si una regla CSS
  falla, el visitante ve dos guiones donde va un usuario.
- Errores en consola al cargar: 0. Red: todo 200. Sin scroll horizontal del documento en ninguno de
  los dos tamaños; el nav móvil desborda dentro de su propio contenedor, como debe.
- overused-font del detector no es falso positivo, pero tampoco un defecto: Instrument Sans es la
  cara elegida y declarada.

## Preguntas

1. Si el posicionamiento es «llegamos al precio», ¿por qué la pantalla que verá el 90 % no contiene
   un solo precio?
2. ¿Qué perdería la página si deslogueada no hubiera sidebar y el único elemento persistente fuera
   el logotipo? Ahora mismo son nueve promesas incumplidas a un clic.
3. DESIGN.md declara Operate; esta superficie es Persuade. ¿Se decidió, o la landing es Operate
   porque nunca se construyó nada distinto? La prueba es que «sin sesión» y «con sesión pero sin
   datos» comparten el mismo div y la misma cabecera.

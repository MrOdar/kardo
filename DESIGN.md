# Design

<!-- impeccable:design-schema 1 -->

## Mode

**Operate.** El visitante completa una tarea: cargar, recibir, vender, rebajar,
resolver. La escanabilidad y la consistencia mandan sobre la expresión; la marca
vive en la precisión de los detalles, no en adornos.

El evaluador técnico es audiencia decisiva pero no usuaria: se le gana
demostrando que la herramienta es seria, no decorándola.

## World

**Consola de operación de tienda.** Claro, espacioso y deliberadamente
corporativo —el lenguaje de un SaaS de producto—, con una densidad de datos que
delata que detrás hay un motor y no una maqueta.

Lo que sustituye: la versión anterior era una cabecera verde oscura con pestañas
apretadas sobre un cuerpo blanco plano, métricas en guiones y formularios sin
jerarquía. Era evidencia de qué hace el producto, no autoridad sobre cómo debe
verse.

Dos reglas que gobiernan el conjunto:

1. **El color es información, no decoración.** El azul es estructura y acción.
   Rojo, ámbar y verde aparecen *solo* donde hay un estado de riesgo que
   comunicar. Una pantalla sin riesgo es una pantalla sin color de alerta.
2. **El dinero y el tiempo se leen alineados.** Cifras tabulares siempre, a la
   derecha, para poder comparar columnas con el ojo y no con la calculadora.

## Palette

| Token | Valor | Uso |
|---|---|---|
| `ink` | `#0B1220` | Texto principal, encabezados |
| `ink.soft` | `#3D4A63` | Texto secundario |
| `ink.faint` | `#5C6881` | Etiquetas, metadatos |
| `brand` | `#3F6FD0` | Acción primaria, estado activo, foco |
| `brand.deep` | `#2F4F86` | Barra lateral, superficies de marca |
| `brand.soft` | `#DBE6FA` | Rellenos pastel: numerales, viñetas |
| `brand.tint` | `#EEF3FD` | Fondo de selección y de estado activo |
| `canvas` | `#F3F6FC` | Fondo de la aplicación |
| `surface` | `#FFFFFF` | Tarjetas, tablas, paneles |
| `line` | `#E2E8F4` | Bordes y separadores |
| `risk.red` | `#B3261E` | Vencido o ≤48 h |
| `risk.amber` | `#8A5A00` | Vence en ≤5 días |
| `risk.green` | `#116A45` | Sano, devuelto al stock |

El azul vive en un registro pastel a propósito. Un azul eléctrico compite con el
rojo de un vencimiento, y en esta pantalla lo que tiene que gritar es el riesgo,
no la marca. Bajado el voltaje, `brand` sigue pasando 4.78:1 con texto blanco y
la barra lateral 8:1; medido en ejecución, no estimado.

Los tres de riesgo están elegidos para pasar 4.5:1 sobre su propio fondo tintado
(`*-tint`), que es como aparecen: chips, no franjas.

## Type

- **Instrument Sans** para toda la interfaz. Tiene carácter —algo estrechada,
  terminaciones decididas— sin salirse del registro corporativo, y evita el
  defecto de categoría.
- **IBM Plex Mono** solo para identificadores que se cotejan carácter a carácter:
  SKU, código de lote, id de traza. Es dato, no disfraz técnico.
- Cifras siempre `tabular-nums`.
- Escala: 28/20/16/14/13/11. Saltos de peso 400 → 500 → 600; nunca 700 salvo en
  la cifra principal de un indicador.

## Layout

**Shell de aplicación**, no una página con pestañas:

- **Barra lateral fija** (`brand.deep`) con el logotipo, las secciones, la
  sesión y el botón de demo. En móvil colapsa a una barra superior desplazable.
  Las nueve secciones no van en una lista plana —nueve nombres iguales obligan
  a leerlos todos para encontrar uno— sino agrupadas por el momento en que se
  usan: lo primero de hoy suelto arriba, y debajo **Decidir hoy**, **Operar** y
  **Revisar**. Los rótulos desaparecen en móvil, donde la navegación es una
  fila que se desplaza y un rótulo sólo robaría ancho.
- **La aplicación abre en una decisión, no en una tabla.** La primera sección es
  «Lo primero de hoy»: las prioridades ordenadas por lo que cuesta dejarlas para
  mañana, cada línea con el botón que lleva adonde se resuelve, y debajo el
  reparto de vencimientos, los últimos movimientos y lo próximo en salir. No
  consulta nada propio: se arma con lo que las demás secciones ya trajeron, para
  que no pueda contar una cifra distinta de la que muestra la sección de al
  lado. Sin sesión abierta, esa misma pantalla explica qué es Kardo y deja el
  único botón que hace falta.
- **Cabecera de sección** uniforme: título, una línea de propósito y las acciones
  primarias alineadas a la derecha.
- **Contenido** en ancho máximo de 1180 px sobre `canvas`, con tarjetas
  `surface` de borde `line` y sombra de desplazamiento real.

Rejilla de 4 px. Agrupación apretada dentro de un bloque, separación generosa
entre bloques, más aire encima de un encabezado que debajo.

## Components

- **Logotipo.** Tres barras redondeadas en cola; la de arriba va desplazada a
  la derecha y es la única opaca, porque es la que sale primero. Es la regla
  entera del producto dicha en 28 px, y el mismo dibujo sirve de favicon. Se
  eligió una marca dibujada y no una inicial en un cuadrado: la inicial no dice
  nada y se parece a la de cualquier otro producto.
- **Chip de estado.** Reemplaza el filete lateral de 4 px. Fondo tintado, texto
  del color de riesgo, y el dato dentro: `2 d`, `Vencido`. El color nunca viaja
  solo.
- **Tabla.** Cabecera en `canvas` con etiquetas de 11 px en mayúsculas suaves,
  filas separadas por `line`, numéricas a la derecha, hover tintado.
- **Indicador.** Etiqueta pequeña arriba, cifra grande debajo, y una línea de
  contexto que dice qué significa. Nunca una rejilla de cuatro tarjetas iguales:
  el valor en riesgo manda y los demás lo acompañan a menor tamaño. Cuando van
  en tira, los separadores son el hueco de la rejilla teñido de `line`, para que
  caigan bien con dos columnas o con cuatro.
- **Fila de prioridad.** Chip de estado, el qué y el porqué en dos líneas, y a
  la derecha el botón que abre la sección donde se resuelve. En móvil el botón
  baja a su propia línea antes que estrujar el título.
- **Buscador global** (`Ctrl+K`). Con ocho secciones, llegar a un SKU obligaba a
  elegir sección primero. Se abre arriba y no al centro: la lista crece hacia
  abajo y el ojo no viaja. Abrir un resultado deja la búsqueda hecha, no sólo
  la sección abierta.
- **Serie temporal.** Eje desde cero, porque son soles: una escala recortada
  convierte una variación de céntimos en un precipicio. Con menos de dos puntos
  no se dibuja nada; una línea plana de un día parece un dato y no lo es.
- **Estado vacío.** Nunca una palabra sola. Distingue «ya está todo resuelto» de
  «todavía no ha pasado nada», y en el segundo caso dice qué llegará a aparecer
  ahí.
- **Botones.** Primario `brand` sólido; secundario borde `line` sobre `surface`;
  destructivo solo en `risk.red` y solo para resolver pérdidas.

## Browser surfaces

Se tematizan explícitamente, porque son la señal más barata de que la página fue
construida: selección de texto, cursor de inserción, anillo de foco (`brand`,
2 px, con separación), barras de desplazamiento, y numerales tabulares.

## Motion

Un solo momento autorizado: el cambio de sección entra con un desplazamiento
corto y una aparición, con salida exponencial desde un estado ya visible. Nada
más se anima. `prefers-reduced-motion` lo anula.

## Dónde vive

El tema (paleta, tipografías, sombras) está en `tailwind.config.js`; las clases
propias —`.nav-item`, `.card`, `.chip`, `.th`, `.section-title`— en
`src/main/css/kardo.css`. De ahí sale `static/kardo.css`, que es lo que viaja
en el jar y hay que **recompilar y versionar** tras tocar cualquiera de los
dos: `npm run css`. El CI reconstruye y falla si lo versionado no coincide.

Antes el tema vivía en un `<script>` dentro del HTML y Tailwind se cargaba
desde su CDN, lo que ataba el aspecto de la aplicación a que un tercero
contestara: si no lo hacía, la interfaz no se degradaba, salía entera sin
estilos. Compilar también permitió cerrar `script-src` a `'self'`.

## Refuse

- Filetes de color de más de 1 px en tarjetas, filas o avisos.
- Rejillas de tarjetas iguales como estructura de página.
- Emoji o glifos en lugar de iconos: los iconos son SVG dibujados, trazo 1.5.
- Degradados en texto.
- Monoespaciada fuera de identificadores y datos.

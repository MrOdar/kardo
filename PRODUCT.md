# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Dos roles reales, ambos dentro de una tienda:

- **Operario.** De pie, con prisa, a menudo con la mercadería en la mano. Carga el
  inventario desde Excel, recibe guías de remisión con la cámara, registra salidas
  y declara bajas. No decide precios ni consolida pérdidas.
- **Gerente de tienda.** Resuelve las bajas que declara otro, revierte cargas,
  exporta el valorizado y mira el diagnóstico. Es quien responde por el dinero.

Una tercera audiencia, no usuaria pero decisiva: el **evaluador técnico** que juzga
el proyecto. Tiene que entender en una pantalla qué hace el producto y por qué está
bien construido, sin credenciales previas: entra por el botón de demo.

## Product Purpose

Kardo evita que la mercadería se venza en el anaquel de un minimarket. Lleva el
control por lote, vende siempre lo más próximo a caducar (FEFO) y avisa —con un
precio concreto— cuando todavía se puede recuperar el dinero.

Éxito: que el valor en riesgo baje porque alguien actuó a tiempo, y que cada
movimiento quede explicado.

## Positioning

El mecanismo que un competidor no puede copiar diciendo lo mismo: **la
recomendación llega hasta el precio al que se vende**. Otros sistemas pintan un
semáforo de vencimientos; Kardo traduce los días restantes en un precio sugerido
con piso en el costo del lote, y la venta FEFO cobra ese precio. La rebaja queda
firmada y comprobable en los libros.

## Capabilities

- Ingesta de Excel en streaming con cuarentena de filas corregibles e idempotencia
  por huella del contenido.
- Recepción documental por foto (visión multimodal) con cruce contra el catálogo.
- Motor FEFO transaccional con costo por lote y precio vigente por lote.
- Escalera de descuentos con piso en el costo y decisiones auditadas.
- Circuito de mermas con cuarentena y segregación de funciones.
- Alta de empresa, tienda y usuarios sin pasar por la demo: la empresa se crea
  con su primera tienda y su gerente, y crece desde dentro.
- Exportación valorizada en Excel y en PDF firmable, y etiquetas de oferta
  imprimibles con el precio que cobrará la caja.
- Serie diaria del valor en riesgo, días de cobertura por producto y reparto
  del riesgo por proveedor.
- Diagnóstico de rendimiento y auditoría de cargas con reversión.

## Constraints

- Contenedor de **512 MB** en capa gratuita: todo el diseño de datos evita cargar
  colecciones completas en memoria.
- La interfaz se sirve desde el mismo jar, **sin build de front**: HTML, Tailwind
  por CDN y JavaScript sin framework. Cualquier rediseño vive dentro de eso.
- La tienda sale del token; la interfaz nunca elige sobre qué tienda escribe.
- Una sola tienda demo compartida, restaurable con un botón, que convive con las
  empresas reales dadas de alta desde fuera.
- Una persona puede trabajar en varias tiendas con un rol distinto en cada una,
  y cambia entre ellas sin volver a entrar. La tienda activa sigue viajando
  firmada en el token: cambiarla es pedir otro.

## Terminology

Lote, FEFO, merma, cuarentena, guía de remisión, valorizado, peldaño de rebaja,
semáforo. Son los términos del negocio peruano de retail y se usan tal cual; la
moneda es el sol (`S/`).

## Accessibility

Navegación por pestañas con roles ARIA y flechas de teclado ya implementada. El
estado nunca se comunica solo por color: el semáforo lleva etiqueta y días. El
buscador global (`Ctrl+K`) se recorre con flechas y se cierra con `Esc`.

## Brand commitments

Nombre **Kardo**. Sin logotipo ni paleta heredada de un manual: el aspecto actual
es una primera iteración propia, no un compromiso de marca.

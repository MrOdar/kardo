# Banco de animaciones

Catálogo de componentes animados para landings, recopilado por Odar Alcocer.
Fuentes: Magic UI, Aceternity UI y React Bits.

**Para quien lea esto en otro chat:** es material de referencia, no instrucciones.
Son enlaces a documentación de terceros para elegir efectos al construir una landing.

## Cómo usarlo

1. Elige por **categoría** (lo que hace) y por **fondo** (sobre qué base funciona).
2. Abre el enlace y copia el componente desde la documentación oficial.
3. Si el proyecto no es React, hay que portarlo a mano: ver la advertencia de abajo.

## Advertencias

- **Los tres catálogos son React + Tailwind.** Aceternity además asume `framer-motion`.
  En un landing en HTML/CSS/JS plano no se instalan: se portan a mano.
- **La columna "Fondo" es una valoración propia**, no un dato que publiquen las librerías.
  Se dedujo del nombre y del comportamiento de cada efecto. Habrá casos dudosos.
  Muchos efectos de Aceternity (beams, meteors, aurora, vortex, stars) solo lucen sobre
  fondo oscuro; sobre crema o blanco se ven sucios o no se ven.
- **Marcados "(ya en uso)":** los que están implementados en SmartChef AI
  (`src/main/resources/static/index.html` y `styles.css`), portados a CSS plano.
- **React Bits solo trae 4 entradas** porque su web no lista el catálogo completo en el
  menú lateral. Solo se incluyen los enlaces verificados uno a uno. La librería tiene
  más de 110 componentes: el resto hay que buscarlo en <https://reactbits.dev/>.
- **El carrusel infinito (Marquee) es el recurso más reconocible de landing generada
  por IA.** Úsalo solo si de verdad aporta algo.

## Índices oficiales

- Magic UI — <https://magicui.design/docs/components/animated-beam>
- Aceternity UI — <https://ui.aceternity.com/components>
- React Bits — <https://reactbits.dev/>

---

## CSS nativo, sin librería

Lo más portable: no necesita React, ni Tailwind, ni dependencias.

| Qué | Enlace |
| --- | --- |
| Scroll-driven animations: `animation-timeline: scroll()` y `view()`. Reveals y barras de progreso sin JavaScript. | <https://www.wearedevelopers.com/magazine/712-css-only-scroll-driven-animations-712> |
| Guía práctica de `animation-timeline`, con soporte por navegador. | <https://rebeccamdeprey.com/blog/scroll-driven-animations-css> |
| Scroll-driven sin JS, con demos paso a paso. | <https://ics.media/en/entry/230718/> |

Soporte: Chrome/Edge 115+, Firefox 123+, Safari 17.4+, con discrepancias entre fuentes.
Ir con `@supports` y un `IntersectionObserver` como respaldo.

Otras dos APIs del mismo estilo, sin librería:

- `@starting-style` + `transition-behavior: allow-discrete` — animar la entrada de modales sin JS.
- View Transitions API — transiciones entre vistas de una SPA.

---

## Catálogo

### Fondos y atmosfera

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Dot Pattern | Magic UI | ambos | <https://magicui.design/docs/components/dot-pattern> |
| Grid Pattern | Magic UI | ambos | <https://magicui.design/docs/components/grid-pattern> |
| Animated Grid Pattern | Magic UI | ambos | <https://magicui.design/docs/components/animated-grid-pattern> |
| Interactive Grid Pattern | Magic UI | ambos | <https://magicui.design/docs/components/interactive-grid-pattern> |
| Flickering Grid | Magic UI | oscuro | <https://magicui.design/docs/components/flickering-grid> |
| Retro Grid | Magic UI | oscuro | <https://magicui.design/docs/components/retro-grid> |
| Hexagon Pattern | Magic UI | ambos | <https://magicui.design/docs/components/hexagon-pattern> |
| Striped Pattern | Magic UI | ambos | <https://magicui.design/docs/components/striped-pattern> |
| Ripple | Magic UI | ambos | <https://magicui.design/docs/components/ripple> |
| Light Rays | Magic UI | oscuro | <https://magicui.design/docs/components/light-rays> |
| Noise Texture | Magic UI | ambos | <https://magicui.design/docs/components/noise-texture> |
| Warp Background | Magic UI | oscuro | <https://magicui.design/docs/components/warp-background> |
| Dither Field | Magic UI | oscuro | <https://magicui.design/docs/components/dither-field> |
| Spotlight | Aceternity | oscuro | <https://ui.aceternity.com/components/spotlight> |
| Spotlight New | Aceternity | oscuro | <https://ui.aceternity.com/components/spotlight-new> |
| Lamp Section Header — Resplandor conico bajo un titulo de seccion, como Linear. | Aceternity | oscuro | <https://ui.aceternity.com/components/lamp-effect> |
| Glowing Effect | Aceternity | ambos | <https://ui.aceternity.com/components/glowing-effect> |
| Aurora Background | Aceternity | oscuro | <https://ui.aceternity.com/components/aurora-background> |
| Wavy Background | Aceternity | oscuro | <https://ui.aceternity.com/components/wavy-background> |
| Background Gradient | Aceternity | ambos | <https://ui.aceternity.com/components/background-gradient> |
| Background Gradient Animation | Aceternity | oscuro | <https://ui.aceternity.com/components/background-gradient-animation> |
| Background Lines | Aceternity | ambos | <https://ui.aceternity.com/components/background-lines> |
| Background Beams | Aceternity | oscuro | <https://ui.aceternity.com/components/background-beams> |
| Background Beams With Collision | Aceternity | oscuro | <https://ui.aceternity.com/components/background-beams-with-collision> |
| Background Boxes | Aceternity | oscuro | <https://ui.aceternity.com/components/background-boxes> |
| Background Ripple Effect | Aceternity | ambos | <https://ui.aceternity.com/components/background-ripple-effect> |
| Dotted Glow Background | Aceternity | oscuro | <https://ui.aceternity.com/components/dotted-glow-background> |
| Grid and Dot Backgrounds | Aceternity | ambos | <https://ui.aceternity.com/components/grid-and-dot-backgrounds> |
| Meteor Effect | Aceternity | oscuro | <https://ui.aceternity.com/components/meteors> |
| Shooting Stars | Aceternity | oscuro | <https://ui.aceternity.com/components/shooting-stars-and-stars-background> |
| Glowing Stars Card | Aceternity | oscuro | <https://ui.aceternity.com/components/glowing-stars-effect> |
| Vortex | Aceternity | oscuro | <https://ui.aceternity.com/components/vortex> |
| Sparkles | Aceternity | oscuro | <https://ui.aceternity.com/components/sparkles> |
| Liquid Glass | Aceternity | ambos | <https://ui.aceternity.com/components/liquid-glass> |
| Cloud Shader | Aceternity | oscuro | <https://ui.aceternity.com/components/cloud-shader> |
| Background Cubes Grid | Aceternity | oscuro | <https://ui.aceternity.com/components/background-cubes-grid> |
| Container Cover | Aceternity | oscuro | <https://ui.aceternity.com/components/container-cover> |

### Efectos especiales

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Animated Beam — Une dos nodos con un haz. Util cuando el contenido ya es un flujo A a B. | Magic UI | ambos | <https://magicui.design/docs/components/animated-beam> |
| Border Beam *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/border-beam> |
| Shine Border | Magic UI | ambos | <https://magicui.design/docs/components/shine-border> |
| Magic Card | Magic UI | ambos | <https://magicui.design/docs/components/magic-card> |
| Glare Hover *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/glare-hover> |
| Meteors | Magic UI | oscuro | <https://magicui.design/docs/components/meteors> |
| Particles | Magic UI | oscuro | <https://magicui.design/docs/components/particles> |
| Confetti — Para un exito puntual: guardar, completar, enviar. | Magic UI | ambos | <https://magicui.design/docs/components/confetti> |
| Cool Mode | Magic UI | ambos | <https://magicui.design/docs/components/cool-mode> |
| Animated Theme Toggler *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/animated-theme-toggler> |
| Orbiting Circles | Magic UI | ambos | <https://magicui.design/docs/components/orbiting-circles> |
| Progressive Blur | Magic UI | ambos | <https://magicui.design/docs/components/progressive-blur> |
| Backlight | Magic UI | oscuro | <https://magicui.design/docs/components/backlight> |
| Pixel Image | Magic UI | ambos | <https://magicui.design/docs/components/pixel-image> |
| Glyph Matrix | Magic UI | oscuro | <https://magicui.design/docs/components/glyph-matrix> |
| Canvas Reveal Effect | Aceternity | oscuro | <https://ui.aceternity.com/components/canvas-reveal-effect> |
| SVG Mask Effect | Aceternity | ambos | <https://ui.aceternity.com/components/svg-mask-effect> |
| Chromatic Image | Aceternity | ambos | <https://ui.aceternity.com/components/chromatic-image> |
| Webcam Pixel Grid | Aceternity | oscuro | <https://ui.aceternity.com/components/webcam-pixel-grid> |
| Scales | Aceternity | ambos | <https://ui.aceternity.com/components/scales> |
| ASCII Art | Aceternity | oscuro | <https://ui.aceternity.com/components/ascii-art> |
| Pixelated Canvas | Aceternity | ambos | <https://ui.aceternity.com/components/pixelated-canvas> |

### Texto

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Text Animate *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/text-animate> |
| Kinetic Text *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/kinetic-text> |
| Number Ticker *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/number-ticker> |
| Typing Animation | Magic UI | ambos | <https://magicui.design/docs/components/typing-animation> |
| Hyper Text — Letras que se barajan hasta fijarse. | Magic UI | ambos | <https://magicui.design/docs/components/hyper-text> |
| Morphing Text | Magic UI | ambos | <https://magicui.design/docs/components/morphing-text> |
| Word Rotate | Magic UI | ambos | <https://magicui.design/docs/components/word-rotate> |
| Animated Shiny Text | Magic UI | ambos | <https://magicui.design/docs/components/animated-shiny-text> |
| Animated Gradient Text | Magic UI | ambos | <https://magicui.design/docs/components/animated-gradient-text> |
| Aurora Text | Magic UI | oscuro | <https://magicui.design/docs/components/aurora-text> |
| Sparkles Text | Magic UI | ambos | <https://magicui.design/docs/components/sparkles-text> |
| Text Reveal — Se revela segun bajas: es scroll, no temporizador. | Magic UI | ambos | <https://magicui.design/docs/components/text-reveal> |
| Dia Text Reveal | Magic UI | ambos | <https://magicui.design/docs/components/dia-text-reveal> |
| Line Shadow Text | Magic UI | ambos | <https://magicui.design/docs/components/line-shadow-text> |
| Spinning Text | Magic UI | ambos | <https://magicui.design/docs/components/spinning-text> |
| Text 3D Flip | Magic UI | ambos | <https://magicui.design/docs/components/text-3d-flip> |
| Text Highlighter | Magic UI | ambos | <https://magicui.design/docs/components/highlighter> |
| Scroll Based Velocity | Magic UI | ambos | <https://magicui.design/docs/components/scroll-based-velocity> |
| Comic Text | Magic UI | ambos | <https://magicui.design/docs/components/comic-text> |
| Video Text | Magic UI | oscuro | <https://magicui.design/docs/components/video-text> |
| Text Generate Effect — Palabras que aparecen como si las escribiera un modelo. | Aceternity | ambos | <https://ui.aceternity.com/components/text-generate-effect> |
| Typewriter Effect | Aceternity | ambos | <https://ui.aceternity.com/components/typewriter-effect> |
| Encrypted Text | Aceternity | ambos | <https://ui.aceternity.com/components/encrypted-text> |
| Flip Words | Aceternity | ambos | <https://ui.aceternity.com/components/flip-words> |
| Layout Text Flip | Aceternity | ambos | <https://ui.aceternity.com/components/layout-text-flip> |
| Container Text Flip | Aceternity | ambos | <https://ui.aceternity.com/components/container-text-flip> |
| Text Hover Effect | Aceternity | oscuro | <https://ui.aceternity.com/components/text-hover-effect> |
| Text Reveal Card | Aceternity | oscuro | <https://ui.aceternity.com/components/text-reveal-card> |
| Hero Highlight | Aceternity | oscuro | <https://ui.aceternity.com/components/hero-highlight> |
| Colourful Text | Aceternity | ambos | <https://ui.aceternity.com/components/colourful-text> |
| Squiggly Text | Aceternity | ambos | <https://ui.aceternity.com/components/squiggly-text> |
| Text Flipping Board — Panel de aeropuerto. | Aceternity | ambos | <https://ui.aceternity.com/components/text-flipping-board> |
| Canvas Text | Aceternity | ambos | <https://ui.aceternity.com/components/canvas-text> |
| Decrypted Text — Caracteres aleatorios que se resuelven en el texto real. | React Bits | ambos | <https://reactbits.dev/text-animations/decrypted-text> |
| Blur Text | React Bits | ambos | <https://reactbits.dev/text-animations/blur-text> |
| ASCII Text | React Bits | oscuro | <https://reactbits.dev/text-animations/ascii-text> |
| Circular Text | React Bits | ambos | <https://reactbits.dev/text-animations/circular-text> |

### Tarjetas

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Bento Grid *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/bento-grid> |
| Neon Gradient Card | Magic UI | oscuro | <https://magicui.design/docs/components/neon-gradient-card> |
| 3D Card Effect | Aceternity | ambos | <https://ui.aceternity.com/components/3d-card-effect> |
| Card Spotlight | Aceternity | oscuro | <https://ui.aceternity.com/components/card-spotlight> |
| Glare Card | Aceternity | ambos | <https://ui.aceternity.com/components/glare-card> |
| Comet Card | Aceternity | oscuro | <https://ui.aceternity.com/components/comet-card> |
| Evervault Card | Aceternity | oscuro | <https://ui.aceternity.com/components/evervault-card> |
| Wobble Card | Aceternity | ambos | <https://ui.aceternity.com/components/wobble-card> |
| Card Stack | Aceternity | ambos | <https://ui.aceternity.com/components/card-stack> |
| Focus Cards — Al pasar sobre una, las demas se desenfocan. | Aceternity | ambos | <https://ui.aceternity.com/components/focus-cards> |
| Expandable Cards | Aceternity | ambos | <https://ui.aceternity.com/components/expandable-card> |
| Hover Effect | Aceternity | ambos | <https://ui.aceternity.com/components/card-hover-effect> |
| Direction Aware Hover | Aceternity | ambos | <https://ui.aceternity.com/components/direction-aware-hover> |
| Draggable Card | Aceternity | ambos | <https://ui.aceternity.com/components/draggable-card> |
| Infinite Moving Cards | Aceternity | ambos | <https://ui.aceternity.com/components/infinite-moving-cards> |
| Tooltip Card | Aceternity | ambos | <https://ui.aceternity.com/components/tooltip-card> |
| Layout Grid | Aceternity | ambos | <https://ui.aceternity.com/components/layout-grid> |
| Bento Grid | Aceternity | ambos | <https://ui.aceternity.com/components/bento-grid> |

### Botones

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Interactive Hover Button *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/interactive-hover-button> |
| Shimmer Button | Magic UI | ambos | <https://magicui.design/docs/components/shimmer-button> |
| Rainbow Button | Magic UI | ambos | <https://magicui.design/docs/components/rainbow-button> |
| Ripple Button | Magic UI | ambos | <https://magicui.design/docs/components/ripple-button> |
| Pulsating Button | Magic UI | ambos | <https://magicui.design/docs/components/pulsating-button> |
| Shiny Button | Magic UI | ambos | <https://magicui.design/docs/components/shiny-button> |
| Magnetic Button — El boton tira del cursor. | Aceternity | ambos | <https://ui.aceternity.com/components/magnetic-button> |
| Hover Border Gradient | Aceternity | ambos | <https://ui.aceternity.com/components/hover-border-gradient> |
| Moving Border | Aceternity | ambos | <https://ui.aceternity.com/components/moving-border> |
| Stateful Button | Aceternity | ambos | <https://ui.aceternity.com/components/stateful-button> |

### Scroll y parallax

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Scroll Progress | Magic UI | ambos | <https://magicui.design/docs/components/scroll-progress> |
| Blur Fade | Magic UI | ambos | <https://magicui.design/docs/components/blur-fade> |
| Tracing Beam — Hilo que te sigue por un articulo largo. | Aceternity | ambos | <https://ui.aceternity.com/components/tracing-beam> |
| Google Gemini Effect | Aceternity | oscuro | <https://ui.aceternity.com/components/google-gemini-effect> |
| Parallax Hero Images | Aceternity | ambos | <https://ui.aceternity.com/components/parallax-hero-images> |
| Sticky Scroll Reveal | Aceternity | ambos | <https://ui.aceternity.com/components/sticky-scroll-reveal> |
| Container Scroll Animation | Aceternity | ambos | <https://ui.aceternity.com/components/container-scroll-animation> |
| Hero Parallax | Aceternity | ambos | <https://ui.aceternity.com/components/hero-parallax> |
| Parallax Grid Scroll | Aceternity | ambos | <https://ui.aceternity.com/components/parallax-scroll> |
| Macbook Scroll | Aceternity | ambos | <https://ui.aceternity.com/components/macbook-scroll> |

### Navegacion

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Dock *(ya en uso)* | Magic UI | ambos | <https://magicui.design/docs/components/dock> |
| Floating Dock | Aceternity | ambos | <https://ui.aceternity.com/components/floating-dock> |
| Floating Navbar | Aceternity | ambos | <https://ui.aceternity.com/components/floating-navbar> |
| Resizable Navbar | Aceternity | ambos | <https://ui.aceternity.com/components/resizable-navbar> |
| Navbar Menu | Aceternity | ambos | <https://ui.aceternity.com/components/navbar-menu> |
| Animated Tabs | Aceternity | ambos | <https://ui.aceternity.com/components/tabs> |
| Sticky Banner | Aceternity | ambos | <https://ui.aceternity.com/components/sticky-banner> |
| Sidebar | Aceternity | ambos | <https://ui.aceternity.com/components/sidebar> |
| Notch | Aceternity | oscuro | <https://ui.aceternity.com/components/notch> |

### Cursor y puntero

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Lens | Magic UI | ambos | <https://magicui.design/docs/components/lens> |
| Pointer | Magic UI | ambos | <https://magicui.design/docs/components/pointer> |
| Smooth Cursor | Magic UI | ambos | <https://magicui.design/docs/components/smooth-cursor> |
| Following Pointer | Aceternity | ambos | <https://ui.aceternity.com/components/following-pointer> |
| Pointer Highlight | Aceternity | ambos | <https://ui.aceternity.com/components/pointer-highlight> |
| Lens | Aceternity | ambos | <https://ui.aceternity.com/components/lens> |

### Formularios y carga

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Placeholders And Vanish Input — El texto se desintegra al enviar. | Aceternity | ambos | <https://ui.aceternity.com/components/placeholders-and-vanish-input> |
| Gooey Input | Aceternity | ambos | <https://ui.aceternity.com/components/gooey-input> |
| File Upload | Aceternity | ambos | <https://ui.aceternity.com/components/file-upload> |
| Signup Form | Aceternity | ambos | <https://ui.aceternity.com/components/signup-form> |
| Multi Step Loader — Pasos que se van marcando durante una espera larga. | Aceternity | ambos | <https://ui.aceternity.com/components/multi-step-loader> |
| Image Generation Loader | Aceternity | ambos | <https://ui.aceternity.com/components/image-generation-loader> |
| Loader | Aceternity | ambos | <https://ui.aceternity.com/components/loader> |

### Overlays y carruseles

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Hero Video Dialog | Magic UI | ambos | <https://magicui.design/docs/components/hero-video-dialog> |
| Animated Modal | Aceternity | ambos | <https://ui.aceternity.com/components/animated-modal> |
| Animated Tooltip | Aceternity | ambos | <https://ui.aceternity.com/components/animated-tooltip> |
| Link Preview | Aceternity | ambos | <https://ui.aceternity.com/components/link-preview> |
| Images Slider | Aceternity | oscuro | <https://ui.aceternity.com/components/images-slider> |
| Carousel | Aceternity | ambos | <https://ui.aceternity.com/components/carousel> |
| Apple Cards Carousel | Aceternity | ambos | <https://ui.aceternity.com/components/apple-cards-carousel> |
| Animated Testimonials | Aceternity | ambos | <https://ui.aceternity.com/components/animated-testimonials> |

### Datos, 3D y mocks

| Componente | Libreria | Fondo | Enlace |
| --- | --- | --- | --- |
| Animated List — Items que entran de uno en uno. Para feeds e historiales. | Magic UI | ambos | <https://magicui.design/docs/components/animated-list> |
| Marquee — Cuidado: el carrusel infinito es el tic mas reconocible de landing generada. | Magic UI | ambos | <https://magicui.design/docs/components/marquee> |
| Terminal | Magic UI | oscuro | <https://magicui.design/docs/components/terminal> |
| Globe | Magic UI | oscuro | <https://magicui.design/docs/components/globe> |
| Dotted Map | Magic UI | ambos | <https://magicui.design/docs/components/dotted-map> |
| Icon Cloud | Magic UI | ambos | <https://magicui.design/docs/components/icon-cloud> |
| Avatar Circles | Magic UI | ambos | <https://magicui.design/docs/components/avatar-circles> |
| Tweet Card | Magic UI | ambos | <https://magicui.design/docs/components/tweet-card> |
| Animated Circular Progress Bar | Magic UI | ambos | <https://magicui.design/docs/components/animated-circular-progress-bar> |
| File Tree | Magic UI | ambos | <https://magicui.design/docs/components/file-tree> |
| Code Comparison | Magic UI | ambos | <https://magicui.design/docs/components/code-comparison> |
| Safari | Magic UI | ambos | <https://magicui.design/docs/components/safari> |
| iPhone | Magic UI | ambos | <https://magicui.design/docs/components/iphone> |
| Android | Magic UI | ambos | <https://magicui.design/docs/components/android> |
| Images Badge | Aceternity | ambos | <https://ui.aceternity.com/components/images-badge> |
| Keyboard | Aceternity | ambos | <https://ui.aceternity.com/components/keyboard> |
| Terminal | Aceternity | oscuro | <https://ui.aceternity.com/components/terminal> |
| Timeline | Aceternity | ambos | <https://ui.aceternity.com/components/timeline> |
| Compare — Antes y despues con tirador. | Aceternity | ambos | <https://ui.aceternity.com/components/compare> |
| World Map | Aceternity | ambos | <https://ui.aceternity.com/components/world-map> |
| GitHub Globe | Aceternity | oscuro | <https://ui.aceternity.com/components/github-globe> |
| Code Block | Aceternity | oscuro | <https://ui.aceternity.com/components/code-block> |
| 3D Globe | Aceternity | oscuro | <https://ui.aceternity.com/components/3d-globe> |
| 3D Animated Pin | Aceternity | ambos | <https://ui.aceternity.com/components/3d-pin> |
| 3D Marquee | Aceternity | oscuro | <https://ui.aceternity.com/components/3d-marquee> |

---

Generado el 2026-11-14. Pagina equivalente con buscador: https://claude.ai/artifact/TL44ijV1Vssqd7y8yJZApi (privada, solo la abre su dueno).

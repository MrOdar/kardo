/*
 * El cruce de ventas contra existencias, y lo que se niega a inventar.
 *
 * Lo que vigila. Un panel de analitica miente de dos maneras baratas: dibuja
 * en el cero lo que no sabe, y promedia promedios. La primera pone un producto
 * sin ventas en el origen, donde parece el que mejor rota; la segunda da una
 * cobertura de familia que no es la de ninguno de sus productos. Las dos
 * producen cifras que alguien usaria para comprar, y ninguna de las dos se ve
 * mirando el grafico: hay que medirlas.
 *
 * Tambien vigila que recargar no duplique los oyentes de los selectores. El
 * cargador se llama cada vez que se entra en la pestaña, y con
 * `addEventListener` el tercer cargado redibujaria tres veces por cada cambio.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { abrirPagina } from './arnes.js';

/*
 * Cuatro filas con un caso cada una:
 *  - Leche: vende y tiene stock. Cobertura conocida, vence pronto.
 *  - Arroz: vende y tiene stock, pero no se vence nunca de cerca.
 *  - Pan:   tiene stock y NO vendio nada. Sin cobertura.
 *  - Atun:  vendio y se agoto. Sin stock y sin vencimiento.
 */
const ROTACION = [
  { sku: 'SKU-LEC', nombre: 'Leche entera 1 L', categoria: 'LACTEOS',
    vendidas: '300', ingreso: '1500.00', margen: '450.00',
    stock_unidades: '200', stock_valor: '700.00',
    dias_hasta_vencer: 4, dias_cobertura: '20.0' },
  { sku: 'SKU-ARR', nombre: 'Arroz extra 5 kg', categoria: 'ABARROTES',
    vendidas: '60', ingreso: '1800.00', margen: '300.00',
    stock_unidades: '40', stock_valor: '960.00',
    dias_hasta_vencer: 300, dias_cobertura: '20.0' },
  { sku: 'SKU-PAN', nombre: 'Pan de molde', categoria: 'PANADERIA',
    vendidas: '0', ingreso: '0', margen: '0',
    stock_unidades: '50', stock_valor: '100.00',
    dias_hasta_vencer: 3, dias_cobertura: null },
  { sku: 'SKU-ATU', nombre: 'Atún en aceite', categoria: 'ABARROTES',
    vendidas: '90', ingreso: '540.00', margen: '180.00',
    stock_unidades: '0', stock_valor: '0',
    dias_hasta_vencer: null, dias_cobertura: null }
];

let pagina;

afterEach(() => pagina?.cerrar());

/** Siembra la rotacion y pinta las dos vistas, sin pasar por el login. */
function conRotacion(filas = ROTACION) {
  pagina = abrirPagina();
  pagina.sembrar({ rotation: filas, salesWindow: 30 });
  pagina.evaluar('renderRotationBars(); prepararCruce();');
  return pagina;
}

describe('lo que mas sale frente a su stock', () => {

  it('ordena por unidades vendidas y deja fuera lo que no se vendio', () => {
    const { $ } = conRotacion();
    const texto = $('rotation-bars').textContent;

    expect(texto).toContain('Leche entera 1 L');
    expect(texto).toContain('Atún en aceite');
    // Pan no vendio ni una unidad: no tiene sitio en un ranking de salida.
    expect(texto).not.toContain('Pan de molde');
  });

  it('avisa cuando hay mas dias de anaquel que de vida al lote', () => {
    const { $ } = conRotacion();
    const texto = $('rotation-bars').textContent;

    // Leche: 20 dias de cobertura y el lote vence en 4.
    expect(texto).toContain('20 d de venta y el lote vence en 4');
    // Arroz: 20 dias de cobertura y 300 de vida. Nada que avisar.
    expect(texto).toContain('60 vendidas · quedan 40');
  });

  /*
   * Los dias restantes son negativos cuando la fecha ya paso. "Vence en −2" no
   * es una manera rara de decirlo: dice lo contrario de lo que pasa.
   */
  it('un lote con la fecha pasada se dice vencido y no "vence en −2"', () => {
    const { $ } = conRotacion([{ ...ROTACION[0], dias_hasta_vencer: -2 }]);
    const texto = $('rotation-bars').textContent;

    expect(texto).toContain('ya está vencido');
    expect(texto).not.toContain('-2');
    expect(texto).not.toContain('−2');
  });

  it('un lote que vence hoy se dice hoy y no "en 0"', () => {
    const { $ } = conRotacion([{ ...ROTACION[0], dias_hasta_vencer: 0 }]);

    expect($('rotation-bars').textContent).toContain('vence hoy');
  });

  it('las dos barras comparten escala', () => {
    const { $ } = conRotacion();
    const anchos = [...$('rotation-bars').querySelectorAll('[style*="width"]')]
      .map((nodo) => Number(nodo.style.width.replace('%', '')));

    // El techo es 300 (lo vendido de leche). Su propia barra llena el 100 %, y
    // sus 200 de stock ocupan dos tercios DE LA MISMA escala.
    expect(anchos[0]).toBeCloseTo(100, 1);
    expect(anchos[1]).toBeCloseTo(66.67, 1);
  });

  it('sin ventas en la ventana lo dice en vez de dibujar nada', () => {
    const { $ } = conRotacion([ROTACION[2]]);

    expect($('rotation-bars').textContent).toContain('No hay ventas registradas');
  });
});

describe('cruce de ejes a eleccion', () => {

  it('abre cruzando cobertura con valor en stock', () => {
    const { $ } = conRotacion();

    expect($('cross-x').value).toBe('cobertura');
    expect($('cross-y').value).toBe('stock_valor');
    expect($('cross-th-x').textContent).toBe('Días de cobertura');
    expect($('cross-th-y').textContent).toBe('Valor en stock');
  });

  it('no dibuja en el cero lo que no sabe, y lo cuenta aparte', () => {
    const { $ } = conRotacion();

    // Con cobertura en un eje, solo leche y arroz tienen las dos cifras.
    expect($('cross-chart').querySelectorAll('circle')).toHaveLength(2);
    expect($('cross-count').textContent).toContain('2 puntos');
    expect($('cross-count').textContent).toContain('2 sin una de las dos cifras');
  });

  it('cambiar de eje cambia los puntos sin pedir nada al servidor', () => {
    const { $, llamadas, window } = conRotacion();
    const antes = llamadas.length;

    $('cross-x').value = 'vendidas';
    $('cross-y').value = 'stock_u';
    $('cross-x').dispatchEvent(new window.Event('change'));

    // Vendidas y stock existen para los cuatro: ninguno queda fuera.
    expect($('cross-chart').querySelectorAll('circle')).toHaveLength(4);
    expect($('cross-count').textContent).toBe('4 puntos');
    expect(llamadas.length).toBe(antes);
  });

  it('recargar no duplica los oyentes de los selectores', () => {
    const { $, window } = conRotacion();

    // El espia se instala ANTES de volver a cargar: `prepararCruce` asigna
    // `onchange = renderCross`, y una declaracion de funcion de un guion
    // clasico es una propiedad de globalThis, de modo que sustituirla aqui es
    // lo que acaba en el selector.
    pagina.evaluar(`
      globalThis.__cuenta = 0;
      globalThis.renderCross = () => { globalThis.__cuenta += 1; };
      prepararCruce(); prepararCruce(); prepararCruce();
      globalThis.__cuenta = 0;
    `);

    $('cross-group').dispatchEvent(new window.Event('change'));

    // Tres cargados, un solo dibujo. Con addEventListener serian tres.
    expect(pagina.evaluar('globalThis.__cuenta')).toBe(1);
  });

  it('agrupando por familia suma el dinero y recalcula la cobertura', () => {
    const { $, window } = conRotacion();

    $('cross-group').value = 'familia';
    $('cross-x').value = 'cobertura';
    $('cross-y').value = 'stock_valor';
    $('cross-group').dispatchEvent(new window.Event('change'));

    const abarrotes = [...$('cross-rows').children]
      .find((fila) => fila.textContent.includes('Abarrotes'));
    const celdas = [...abarrotes.children].map((celda) => celda.textContent.trim());

    // Abarrotes junta arroz (40 u de stock, S/ 960, 60 vendidas) con atun (sin
    // stock, 90 vendidas): 40 unidades sobre un ritmo de (60 + 90) / 30 = 5 al
    // dia son 8 dias de anaquel. Promediando las coberturas de cada producto
    // saldrian 20, que no es la de ninguno de los dos.
    expect(celdas[1]).toBe('8 d');
    expect(celdas[2]).toContain('960');
  });

  it('agrupando por familia desactiva el filtro por familia', () => {
    const { $, window } = conRotacion();

    expect($('cross-family').disabled).toBe(false);

    $('cross-group').value = 'familia';
    $('cross-group').dispatchEvent(new window.Event('change'));

    // Filtrar por una familia cuando cada punto ES una familia dejaria uno.
    expect($('cross-family').disabled).toBe(true);
  });

  it('el filtro por familia recorta los puntos', () => {
    const { $, window } = conRotacion();

    $('cross-x').value = 'vendidas';
    $('cross-y').value = 'stock_u';
    $('cross-family').value = 'ABARROTES';
    $('cross-family').dispatchEvent(new window.Event('change'));

    expect($('cross-chart').querySelectorAll('circle')).toHaveLength(2);
    expect($('cross-rows').textContent).toContain('Arroz extra 5 kg');
    expect($('cross-rows').textContent).not.toContain('Leche entera 1 L');
  });

  it('el color del punto sale de lo que le queda al lote', () => {
    const { $, window } = conRotacion();

    $('cross-x').value = 'vendidas';
    $('cross-y').value = 'stock_u';
    $('cross-x').dispatchEvent(new window.Event('change'));

    const colores = [...$('cross-chart').querySelectorAll('circle')]
      .map((nodo) => nodo.getAttribute('fill'));

    // Leche vence en 4 dias (rojo), arroz en 300 (azul), pan en 3 (rojo) y
    // atun no tiene existencias, asi que no tiene nada que vencer (gris).
    expect(colores).toContain('#B3261E');
    expect(colores).toContain('#3F6FD0');
    expect(colores).toContain('#5C6881');
  });

  it('el nombre del producto va escapado en el titulo del punto', () => {
    const { $ } = conRotacion([{
      ...ROTACION[0], nombre: 'Leche <img src=x onerror=alert(1)>'
    }]);

    const titulo = $('cross-chart').querySelector('title');
    expect(titulo.textContent).toContain('<img src=x');
    expect($('cross-chart').querySelector('img')).toBeNull();
  });

  /*
   * La cola larga. Un producto que vendio una unidad en treinta dias y tiene
   * trescientas en el anaquel son nueve mil dias de cobertura. Con la escala
   * entera, los otros sesenta se apilan en una columna contra el eje y el
   * grafico no dice nada: se vio pasando datos de prueba por el navegador.
   */
  const CON_COLA = Array.from({ length: 20 }, (_, i) => ({
    sku: `SKU-${i}`, nombre: `Producto ${i}`, categoria: 'ABARROTES',
    vendidas: '30', ingreso: '100.00', margen: '10.00',
    stock_unidades: String(i === 0 ? 9000 : 10 + i), stock_valor: '50.00',
    dias_hasta_vencer: 30, dias_cobertura: null
  }));

  it('el eje se corta en el percentil 95 y lo dice', () => {
    const { $, window } = conRotacion(CON_COLA);

    $('cross-x').value = 'stock_u';
    $('cross-y').value = 'stock_valor';
    $('cross-x').dispatchEvent(new window.Event('change'));

    // Los veinte siguen dibujados: se recorta la escala, no el dato.
    expect($('cross-chart').querySelectorAll('circle')).toHaveLength(20);
    expect($('cross-chart').textContent).toContain('1 punto se sale de la escala');
    // El valor atipico va hueco, y su globo conserva la cifra de verdad.
    const hueco = [...$('cross-chart').querySelectorAll('circle')]
      .filter((nodo) => nodo.getAttribute('fill-opacity') === '0');
    expect(hueco).toHaveLength(1);
    expect(hueco[0].querySelector('title').textContent).toContain('9000 u');
  });

  it('con pocos puntos no se corta nada', () => {
    const { $, window } = conRotacion(CON_COLA.slice(0, 6));

    $('cross-x').value = 'stock_u';
    $('cross-y').value = 'stock_valor';
    $('cross-x').dispatchEvent(new window.Event('change'));

    // Un percentil sobre seis valores dejaria fuera uno de cada seis: eso no
    // es recortar la escala, es recortar el dato.
    expect($('cross-chart').textContent).not.toContain('de la escala');
    expect([...$('cross-chart').querySelectorAll('circle')]
      .filter((nodo) => nodo.getAttribute('fill-opacity') === '0')).toHaveLength(0);
  });

  it('sin datos lo dice y no deja un lienzo vacio', () => {
    const { $ } = conRotacion([]);

    expect($('cross-chart').textContent).toContain('No hay existencias ni ventas');
    expect($('cross-rows').children).toHaveLength(0);
  });
});

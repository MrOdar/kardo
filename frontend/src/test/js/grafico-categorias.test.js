/*
 * El filtro del grafico por familias.
 *
 * Lo que vigila. Este grafico se construyo para poder quitar y poner familias,
 * y la primera version volvia a crear todos los chips en cada pulsacion: el
 * boton que acababas de pulsar desaparecia a mitad del click y el foco se iba
 * al body, de modo que con el teclado no se podia quitar una segunda familia
 * sin volver a tabular desde el principio. No se ve haciendo click con el
 * raton, que es como se probo la primera vez.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { abrirPagina } from './arnes.js';

const FAMILIAS = [
  { categoria: 'PANADERIA', productos: 8, unidades: 120, valor: '907.10',
    en_riesgo: '605.30', productos_sin_salida: 5, sin_salida: '605.30', cobertura_media: '5.4' },
  { categoria: 'EMBUTIDOS', productos: 8, unidades: 90, valor: '1214.80',
    en_riesgo: '81.00', productos_sin_salida: 3, sin_salida: '387.00', cobertura_media: '8.5' },
  { categoria: 'ABARROTES', productos: 20, unidades: 400, valor: '5100.00',
    en_riesgo: '0', productos_sin_salida: 0, sin_salida: '0', cobertura_media: '23.8' }
];

let pagina;

afterEach(() => pagina?.cerrar());

/** Pinta el grafico con las familias dadas, sin pasar por el login. */
function conFamilias(familias = FAMILIAS) {
  pagina = abrirPagina();
  pagina.sembrar({ categories: familias });
  // El Set se crea dentro del realm: el guion llama a .has/.delete sobre el
  // suyo, y uno construido en la prueba es de otro realm.
  pagina.evaluar('state.categoriesOff = new Set(); renderCategories();');
  return pagina;
}

describe('grafico por familias', () => {

  it('pinta una barra por familia', () => {
    const { $ } = conFamilias();

    expect($('category-filters').children).toHaveLength(3);
    expect($('category-chart').textContent).toContain('Panaderia');
    expect($('category-chart').textContent).toContain('Embutidos');
  });

  it('el total resume solo lo que esta visible', () => {
    const { $, document } = conFamilias();

    // 605.30 + 387.00 = 992.30 sobre 907.10 + 1214.80 + 5100.00
    expect($('category-total').textContent).toContain('992');

    document.querySelector('[data-categoria="PANADERIA"]').click();

    // Sin panaderia queda solo el 387.00 de embutidos.
    expect($('category-total').textContent).toContain('387');
    expect($('category-total').textContent).not.toContain('992');
  });

  it('quitar una familia la saca del grafico pero no de los chips', () => {
    const { $, document } = conFamilias();

    document.querySelector('[data-categoria="EMBUTIDOS"]').click();

    expect($('category-chart').textContent).not.toContain('Embutidos');
    // El chip sigue ahi: es como se vuelve a poner.
    expect($('category-filters').children).toHaveLength(3);
    expect(document.querySelector('[data-categoria="EMBUTIDOS"]')
      .getAttribute('aria-pressed')).toBe('false');
  });

  it('volver a pulsar la devuelve', () => {
    const { $, document } = conFamilias();
    const chip = () => document.querySelector('[data-categoria="EMBUTIDOS"]');

    chip().click();
    expect($('category-chart').textContent).not.toContain('Embutidos');

    chip().click();
    expect($('category-chart').textContent).toContain('Embutidos');
    expect(chip().getAttribute('aria-pressed')).toBe('true');
  });

  /**
   * El fallo concreto por el que existe este archivo: si `renderCategories`
   * reconstruye los chips en cada pulsacion, el boton pulsado deja de existir y
   * el foco cae al body. Con raton no se nota; con teclado deja el grafico
   * inutilizable a partir del segundo filtro.
   */
  it('el chip pulsado sigue siendo el mismo nodo y conserva el foco', () => {
    const { document } = conFamilias();

    const antes = document.querySelector('[data-categoria="PANADERIA"]');
    antes.focus();
    antes.click();

    const despues = document.querySelector('[data-categoria="PANADERIA"]');
    expect(despues).toBe(antes);
    expect(document.activeElement).toBe(antes);
  });

  it('la escala se rehace con lo que queda, no con el total original', () => {
    const { document } = conFamilias();
    const ancho = (familia) => {
      const barra = [...document.querySelectorAll('#category-chart div[style]')]
        .find((nodo) => nodo.closest('.mb-3\\.5')?.textContent.includes(familia));
      return parseFloat(barra?.style.width ?? '0');
    };

    // Con abarrotes dentro (5100), panaderia (907) es una barra corta.
    const conTodas = ancho('Panaderia');

    document.querySelector('[data-categoria="ABARROTES"]').click();

    // Quitada la familia grande, el techo baja y panaderia crece. Sin esto,
    // filtrar no serviria de nada: el resto seguiria aplastado al margen.
    expect(ancho('Panaderia')).toBeGreaterThan(conTodas);
  });

  it('quitarlas todas lo dice, en vez de dejar el hueco en blanco', () => {
    const { $, document } = conFamilias();

    ['PANADERIA', 'EMBUTIDOS', 'ABARROTES']
      .forEach((familia) => document.querySelector(`[data-categoria="${familia}"]`).click());

    expect($('category-chart').textContent).toContain('Quitaste todas las familias');
    expect($('category-total').textContent).toBe('');
  });

  it('sin existencias no deja una tabla vacia', () => {
    const { $ } = conFamilias([]);

    expect($('category-filters').children).toHaveLength(0);
    expect($('category-chart').textContent).toContain('No hay existencias que agrupar');
  });

  /**
   * Una familia sin ventas en la ventana devuelve `cobertura_media` nula desde
   * el servidor a proposito: no se sabe a que ritmo sale. La pagina tiene que
   * decir eso y no un cero, que alguien leeria como "no se vende nada".
   */
  it('una cobertura desconocida no se pinta como cero', () => {
    const { $ } = conFamilias([
      { categoria: 'LICORES', productos: 4, unidades: 30, valor: '800.00',
        en_riesgo: '0', productos_sin_salida: 0, sin_salida: '0', cobertura_media: null }
    ]);

    expect($('category-chart').textContent).not.toMatch(/\b0(\.0)?\s*d\b/);
  });
});

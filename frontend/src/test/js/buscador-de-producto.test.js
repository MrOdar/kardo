/*
 * El buscador de producto del formulario de venta.
 *
 * Antes era un `<select>` con el catalogo entero: con 91 productos, encontrar
 * "Inca Kola" obligaba a recorrer la lista. Ahora se escribe y se filtra.
 *
 * Lo que se vigila es lo que un `<select>` traia de serie y un combobox a mano
 * tiene que ganarse: que el teclado sirva -flechas, Enter, Escape- y que lo que
 * se envia sea el SKU y no el texto que se ve. Esa ultima es la que puede
 * vender el producto equivocado sin que nada se rompa.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { abrirPagina } from './arnes.js';

const CATALOGO = [
  { sku: 'BEB-INC-001', name: 'Gaseosa Inca Kola Sabor Original 500ml', category: 'BEBIDAS', retail_price: '3.50' },
  { sku: 'BEB-INC-002', name: 'Gaseosa Inca Kola Sin Azucar 500ml', category: 'BEBIDAS', retail_price: '3.50' },
  { sku: 'BEB-COC-003', name: 'Gaseosa Coca Cola Sabor Original 500ml', category: 'BEBIDAS', retail_price: '3.50' },
  { sku: 'LAC-GLO-001', name: 'Leche Evaporada Gloria Azul 400g', category: 'LACTEOS', retail_price: '4.50' },
  { sku: 'PAN-BIM-001', name: 'Pan de Molde Bimbo Blanco 600g', category: 'PANADERIA', retail_price: '7.90' }
];

let pagina;

afterEach(() => pagina?.cerrar());

function conCatalogo(catalogo = CATALOGO) {
  pagina = abrirPagina();
  pagina.sembrar({ catalog: catalogo });
  pagina.evaluar('renderSaleOptions()');
  return pagina;
}

/** Simula teclear: el valor y el evento que dispara el filtrado. */
function teclear(pagina, texto) {
  const input = pagina.$('sale-sku');
  input.value = texto;
  input.dispatchEvent(new pagina.window.Event('input', { bubbles: true }));
  return input;
}

function pulsar(pagina, key) {
  pagina.$('sale-sku').dispatchEvent(new pagina.window.KeyboardEvent('keydown', {
    key, bubbles: true, cancelable: true
  }));
}

function opciones(pagina) {
  return [...pagina.document.querySelectorAll('#sale-sku-list [data-sku]')];
}

describe('buscador de producto', () => {

  it('escribir filtra el catalogo', () => {
    const p = conCatalogo();

    teclear(p, 'inca');

    expect(opciones(p).map((o) => o.dataset.sku))
      .toEqual(['BEB-INC-001', 'BEB-INC-002']);
  });

  it('busca tambien por SKU y por familia, no solo por nombre', () => {
    const p = conCatalogo();

    teclear(p, 'LAC-GLO');
    expect(opciones(p)).toHaveLength(1);

    teclear(p, 'panaderia');
    expect(opciones(p).map((o) => o.dataset.sku)).toEqual(['PAN-BIM-001']);
  });

  it('no distingue mayusculas', () => {
    const p = conCatalogo();

    teclear(p, 'COCA');
    expect(opciones(p).map((o) => o.dataset.sku)).toEqual(['BEB-COC-003']);
  });

  /**
   * La prueba que de verdad paga este archivo. Lo que se ve es el nombre y lo
   * que viaja al backend es el SKU: si el formulario leyera el texto visible,
   * `sp_process_fefo_sale` recibiria "Gaseosa Inca Kola..." como SKU y
   * respondería que el producto no existe. O peor, con otro catalogo, acertaria
   * con el producto equivocado.
   */
  it('al elegir, se ve el nombre y se envia el SKU', () => {
    const p = conCatalogo();

    teclear(p, 'coca');
    opciones(p)[0].dispatchEvent(new p.window.MouseEvent('mousedown', { bubbles: true }));

    expect(p.$('sale-sku').value).toBe('Gaseosa Coca Cola Sabor Original 500ml');
    expect(p.$('sale-sku-value').value).toBe('BEB-COC-003');
  });

  /**
   * Si al reescribir se conservara el SKU anterior, alguien que eligio Coca
   * Cola, borro y escribio "Inca" sin elegir acabaria vendiendo Coca Cola con
   * "Inca" a la vista.
   */
  it('reescribir invalida la eleccion anterior', () => {
    const p = conCatalogo();

    teclear(p, 'coca');
    opciones(p)[0].dispatchEvent(new p.window.MouseEvent('mousedown', { bubbles: true }));
    expect(p.$('sale-sku-value').value).toBe('BEB-COC-003');

    teclear(p, 'inca');
    expect(p.$('sale-sku-value').value).toBe('');
  });

  it('con las flechas y Enter se elige sin tocar el raton', () => {
    const p = conCatalogo();

    teclear(p, 'inca');
    pulsar(p, 'ArrowDown');            // primera
    pulsar(p, 'ArrowDown');            // segunda
    pulsar(p, 'Enter');

    expect(p.$('sale-sku-value').value).toBe('BEB-INC-002');
    expect(p.$('sale-sku').value).toBe('Gaseosa Inca Kola Sin Azucar 500ml');
  });

  it('la flecha arriba da la vuelta por el final', () => {
    const p = conCatalogo();

    teclear(p, 'inca');
    pulsar(p, 'ArrowUp');
    pulsar(p, 'Enter');

    // Sin dar la vuelta habria que bajar hasta el final una por una.
    expect(p.$('sale-sku-value').value).toBe('BEB-INC-002');
  });

  it('Escape cierra la lista sin elegir nada', () => {
    const p = conCatalogo();

    teclear(p, 'inca');
    expect(p.$('sale-sku-list').classList.contains('hidden')).toBe(false);

    pulsar(p, 'Escape');

    expect(p.$('sale-sku-list').classList.contains('hidden')).toBe(true);
    expect(p.$('sale-sku-value').value).toBe('');
  });

  /**
   * Un desplegable que se cierra al teclear parece que la tecla no funciono.
   * Mejor decir que no hay nada.
   */
  it('sin coincidencias lo dice, en vez de cerrarse en silencio', () => {
    const p = conCatalogo();

    teclear(p, 'zzzz');

    expect(opciones(p)).toHaveLength(0);
    expect(p.$('sale-sku-list').classList.contains('hidden')).toBe(false);
    expect(p.$('sale-sku-list').textContent).toContain('Ningún producto coincide');
  });

  it('no vuelca el catalogo entero de golpe', () => {
    const muchos = Array.from({ length: 91 }, (_, i) => ({
      sku: `SKU-${i}`, name: `Producto ${i}`, category: 'PRUEBAS', retail_price: '1.00'
    }));
    const p = conCatalogo(muchos);

    teclear(p, '');

    // Abrir y que caigan 91 filas es volver al problema que esto vino a
    // resolver.
    expect(opciones(p).length).toBeLessThanOrEqual(8);
  });

  it('sin catalogo el campo se bloquea y lo explica', () => {
    const p = conCatalogo([]);

    expect(p.$('sale-sku').disabled).toBe(true);
    expect(p.$('sale-sku').placeholder).toContain('Restaura la demo');
  });

  it('el estado de aria acompaña a la lista', () => {
    const p = conCatalogo();
    const input = p.$('sale-sku');

    expect(input.getAttribute('aria-expanded')).toBe('false');

    teclear(p, 'inca');
    expect(input.getAttribute('aria-expanded')).toBe('true');

    pulsar(p, 'ArrowDown');
    // Con aria-activedescendant el lector de pantalla anuncia la opción
    // resaltada aunque el foco siga en el input.
    expect(input.getAttribute('aria-activedescendant')).toBe('sale-sku-opt-0');
    expect(opciones(p)[0].getAttribute('aria-selected')).toBe('true');

    pulsar(p, 'Escape');
    expect(input.hasAttribute('aria-activedescendant')).toBe(false);
  });
});

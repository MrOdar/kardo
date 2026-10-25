/*
 * Kardo SPA.
 *
 * Todo el estado de correccion vive aqui, en el navegador: el servidor no
 * guarda nada entre la carga de un archivo y el envio de las filas corregidas.
 * De ahi que estas estructuras sean la unica fuente de verdad de la pantalla.
 */
const api = '/api/v1';

const state = {
  token: null,
  storeId: 999,
  quarantine: [],   // filas devueltas por la ingesta, editables en la tabla
  scan: null,       // ultima guia leida por la IA
  catalog: []       // productos para el selector de SKU
};

/* ─────────────────────────── utilidades ─────────────────────────── */

const $ = (id) => document.getElementById(id);

const soles = new Intl.NumberFormat('es-PE', { style: 'currency', currency: 'PEN' });

function money(value) {
  const number = Number(value ?? 0);
  return Number.isFinite(number) ? soles.format(number) : '—';
}

/** Concuerda el numero con su sustantivo: "1 fila", "3 filas". */
function plural(count, singular, many) {
  return `${count} ${count === 1 ? singular : many}`;
}

function toast(message, tone = 'info') {
  const colors = {
    info: 'bg-ink text-shell',
    ok: 'bg-risk-green text-white',
    error: 'bg-risk-red text-white'
  };
  const node = document.createElement('p');
  node.className = `pointer-events-auto max-w-md rounded px-4 py-3 text-sm font-medium shadow-lg ${colors[tone]}`;
  node.textContent = message;
  $('toast').replaceChildren(node);
  window.setTimeout(() => node.remove(), tone === 'error' ? 8000 : 4000);
}

function headers(extra = {}) {
  return state.token ? { Authorization: `Bearer ${state.token}`, ...extra } : { ...extra };
}

/**
 * Envuelve fetch para que un error del backend llegue siempre como texto util
 * al operario en vez de como un fallo silencioso en consola.
 */
async function request(path, options = {}) {
  const response = await fetch(`${api}${path}`, { ...options, headers: headers(options.headers) });
  const body = await response.text();
  let parsed = null;
  try {
    parsed = body ? JSON.parse(body) : null;
  } catch {
    parsed = null;
  }
  if (!response.ok) {
    throw new Error(parsed?.message || `${response.status} ${response.statusText}`);
  }
  return parsed;
}

/* ──────────────────────── navegacion por pestañas ──────────────────────── */

const TABS = ['semaforo', 'inventario', 'excel', 'recepcion'];

/**
 * Muestra una pestaña.
 *
 * <p>Se ocultan paneles en lugar de recrearlos: el estado de la cuarentena y de
 * la guia escaneada vive en el DOM, y perderlo al cambiar de pestaña obligaria
 * al operario a repetir el trabajo.
 */
function showTab(name) {
  TABS.forEach((tab) => {
    const selected = tab === name;
    $(`tab-${tab}`).setAttribute('aria-selected', String(selected));
    $(`tab-${tab}`).classList.toggle('text-shell', selected);
    $(`panel-${tab}`).classList.toggle('hidden', !selected);
  });

  // El inventario se recarga al entrar: pudo cambiar por una venta o una merma.
  if (name === 'inventario' && state.token) {
    loadBatches();
  }
}

TABS.forEach((tab) => {
  $(`tab-${tab}`).addEventListener('click', () => showTab(tab));
});

// Flechas entre pestañas, como espera quien navega con teclado.
$('tab-semaforo').parentElement.addEventListener('keydown', (event) => {
  const current = TABS.findIndex((tab) => $(`tab-${tab}`).getAttribute('aria-selected') === 'true');
  const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
  if (!delta) return;

  event.preventDefault();
  const next = TABS[(current + delta + TABS.length) % TABS.length];
  showTab(next);
  $(`tab-${next}`).focus();
});

/* ─────────────────── estado de conexion con el backend ─────────────────── */

async function pingBackend() {
  const dot = $('connection-dot');
  const text = $('connection-text');
  try {
    const response = await fetch(`${api}/ping`);
    if (!response.ok) throw new Error();
    dot.className = 'h-2 w-2 rounded-full bg-risk-green';
    text.textContent = 'Backend en línea';
  } catch {
    dot.className = 'h-2 w-2 rounded-full bg-risk-red';
    text.textContent = 'Sin conexión con el backend';
  }
}

/* ──────────────────────── panel 1: semaforo FEFO ──────────────────────── */

function renderDashboard(dashboard) {
  $('m-red').textContent = dashboard.redAlertBatches;
  $('m-amber').textContent = dashboard.yellowAlertBatches;
  $('m-value').textContent = money(dashboard.valueAtRisk);
  $('m-discrepancy').textContent = dashboard.linesWithDiscrepancy;
  $('m-active').textContent = dashboard.activeBatches;
  $('m-inventory').textContent = money(dashboard.inventoryValue);
  $('fefo-footer').classList.remove('hidden');
}

/** El estado se deriva de los dias restantes, igual que el semaforo del DDL. */
function batchState(days) {
  if (days < 0) return { key: 'expired', label: 'Vencido', tone: 'text-ink' };
  if (days <= 2) return { key: 'red', label: days === 0 ? 'Vence hoy' : `${days} d`, tone: 'text-risk-red' };
  if (days <= 5) return { key: 'amber', label: `${days} d`, tone: 'text-risk-amber' };
  return { key: 'green', label: `${days} d`, tone: 'text-ink-soft' };
}

function renderFefoQueue(rows) {
  const list = $('fefo-queue');
  if (!rows.length) {
    list.innerHTML = '<li class="py-10 text-center text-sm text-ink-faint">No hay lotes activos con stock.</li>';
    return;
  }
  list.replaceChildren(...rows.map((row) => {
    const days = Number(row.dias_restantes);
    const state_ = batchState(days);
    const item = document.createElement('li');
    // En pantallas estrechas se ocultan lote y fecha: lo que no puede faltar es
    // cuanto le queda al lote, que producto es y cuanto dinero hay en juego.
    item.className = `state-${state_.key} flex items-baseline gap-x-3 gap-y-1 border-l-4 py-3 pl-4`;
    item.innerHTML = `
      <span class="num w-14 shrink-0 font-bold ${state_.tone}">${state_.label}</span>
      <span class="min-w-0 flex-1 truncate font-semibold" title="${escapeHtml(row.name)}">${escapeHtml(row.name)}</span>
      <span class="hidden font-mono text-xs text-ink-faint sm:inline">${escapeHtml(row.batch_code)}</span>
      <span class="num w-16 shrink-0 text-right text-sm text-ink-soft">${row.current_qty} u</span>
      <span class="num w-24 shrink-0 text-right text-sm">${money(row.valor)}</span>
      <span class="num hidden w-28 shrink-0 text-right text-sm text-ink-faint sm:inline">${row.expiration_date}</span>`;
    return item;
  }));
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]
  ));
}

async function refreshDashboard() {
  if (!state.token) return;
  const [dashboard, queue] = await Promise.all([
    request('/demo/dashboard'),
    request('/demo/fefo-queue?limit=12')
  ]);
  renderDashboard(dashboard);
  renderFefoQueue(queue);
  renderWasteBatchOptions(queue);
}

/* ───────────────────────── restaurar la demo ───────────────────────── */

async function resetDemo() {
  const button = $('btn-demo');
  button.disabled = true;
  $('demo-spinner').classList.remove('hidden');
  $('demo-label').textContent = 'Restaurando…';

  try {
    const result = await request('/demo/reset', { method: 'POST' });
    applySession(result.session);
    renderDashboard(result.dashboard);
    const queue = await request('/demo/fefo-queue?limit=12');
    renderFefoQueue(queue);
    renderWasteBatchOptions(queue);
    await loadCatalog();
    await loadPendingWaste();
    await loadUploadHistory();
    await loadBatches();
    toast('Tienda demo restaurada. Ya puedes cargar inventario o una guía.', 'ok');
  } catch (error) {
    toast(`No se pudo restaurar la demo: ${error.message}`, 'error');
  } finally {
    button.disabled = false;
    $('demo-spinner').classList.add('hidden');
    $('demo-label').textContent = 'Restaurar tienda demo';
  }
}

/**
 * Guarda la sesion y la muestra en la cabecera.
 *
 * `storeId` se conserva solo para rellenar los cuerpos que lo incluyen por
 * contrato; el backend lo verifica contra el token y rechaza cualquier otro.
 */
function applySession(session) {
  state.token = session.token;
  state.storeId = session.storeId;
  state.session = session;

  const roles = { MANAGER: 'Gerente de tienda', OPERATOR: 'Operario' };
  $('session').classList.remove('hidden');
  $('session-user').textContent = session.email;
  $('session-role').textContent = `${roles[session.role] ?? session.role} · ${session.storeName}`;
}

async function loadCatalog() {
  try {
    state.catalog = await request('/products');
  } catch {
    state.catalog = [];
  }
  renderSaleOptions();
}

function renderSaleOptions() {
  const select = $('sale-sku');
  if (!state.catalog.length) {
    select.innerHTML = '<option value="">Restaura la demo para cargar el catálogo</option>';
    return;
  }
  select.innerHTML = state.catalog
    .map((product) => `<option value="${escapeHtml(product.sku)}">${escapeHtml(product.name)}</option>`)
    .join('');
}

/**
 * Registra una salida real por el motor FEFO y cuenta de que lotes salio.
 *
 * <p>Es el modo mas corto de demostrar la rotacion: el stock se descuenta del
 * lote que vence antes, no del que entro ultimo.
 */
async function registerSale(event) {
  event.preventDefault();
  const sku = $('sale-sku').value;
  const quantity = Number($('sale-qty').value);
  if (!sku) {
    toast('Restaura la tienda demo para cargar el catálogo.', 'error');
    return;
  }

  const button = event.submitter ?? $('sale-form').querySelector('button[type="submit"]');
  button.disabled = true;
  $('sale-result').textContent = '';

  try {
    const sale = await request('/inventory/sales', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sku, quantity })
    });

    const desglose = sale.consumedBatches
      .map((line) => `${line.batchCode} (vence en ${plural(line.daysToExpiry, 'día', 'días')}): ${line.quantity} u`)
      .join(' · ');
    $('sale-result').textContent = `${desglose} — margen ${money(sale.margin)}`;

    await refreshDashboard();
    toast(`Salida registrada: ${plural(quantity, 'unidad', 'unidades')} de ${sale.productName}.`, 'ok');
  } catch (error) {
    $('sale-result').textContent = '';
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

/* ───────────────── pestaña 2: inventario fisico con buscador ───────────────── */

/** Evita una consulta por tecla: solo se busca cuando el operario deja de escribir. */
function debounce(fn, delay = 300) {
  let timer;
  return (...args) => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => fn(...args), delay);
  };
}

async function loadBatches() {
  const query = $('batch-search').value.trim();
  const filter = $('batch-filter').value;

  try {
    const batches = await request(
      `/inventory/batches?status=${filter}&limit=200${query ? `&q=${encodeURIComponent(query)}` : ''}`);
    renderBatches(batches, query);
  } catch (error) {
    toast(error.message, 'error');
  }
}

function renderBatches(batches, query) {
  const body = $('batch-rows');
  $('batch-count').textContent = batches.length
    ? `${plural(batches.length, 'lote', 'lotes')}`
    : 'Sin resultados';

  if (!batches.length) {
    body.innerHTML = `<tr><td colspan="8" class="px-3 py-10 text-center text-sm text-ink-faint">${
      query ? `Nada coincide con «${escapeHtml(query)}».` : 'No hay lotes con existencias.'
    }</td></tr>`;
    return;
  }

  body.replaceChildren(...batches.map((batch) => {
    const state_ = batchState(batch.daysToExpiry);
    const tr = document.createElement('tr');
    tr.className = `state-${state_.key} border-l-4`;
    tr.innerHTML = `
      <td class="num px-3 py-2 ${state_.tone} font-semibold">${state_.label}</td>
      <td class="px-3 py-2 font-semibold">${escapeHtml(batch.productName)}</td>
      <td class="px-3 py-2 font-mono text-xs text-ink-soft">${escapeHtml(batch.sku)}</td>
      <td class="px-3 py-2 font-mono text-xs text-ink-soft">${escapeHtml(batch.batchCode)}</td>
      <td class="num px-3 py-2 text-right">${batch.currentQty}</td>
      <td class="num px-3 py-2 text-right ${batch.quarantinedQty ? 'text-risk-amber font-semibold' : 'text-ink-faint'}">${batch.quarantinedQty}</td>
      <td class="num px-3 py-2 text-right">${money(batch.value)}</td>
      <td class="px-3 py-2 text-ink-soft">${escapeHtml(batch.status)}</td>`;
    return tr;
  }));
}

$('batch-search').addEventListener('input', debounce(loadBatches));
$('batch-filter').addEventListener('change', loadBatches);

/* ───────────────── pestaña 2: mermas con aprobacion del gerente ───────────────── */

const WASTE_REASONS = {
  EXPIRATION: 'Vencimiento',
  DAMAGE: 'Daño',
  COLD_CHAIN_FAILURE: 'Cadena de frío',
  RECALL: 'Retiro del proveedor'
};

/** El selector de lotes se alimenta de la misma cola FEFO que muestra el panel 1. */
function renderWasteBatchOptions(rows) {
  const select = $('waste-batch');
  if (!rows.length) {
    select.innerHTML = '<option value="">No hay lotes con stock</option>';
    return;
  }
  select.innerHTML = rows.map((row) =>
    `<option value="${row.batch_id}">${escapeHtml(row.name)} · ${escapeHtml(row.batch_code)} `
    + `(${row.current_qty} u, vence en ${plural(Number(row.dias_restantes), 'día', 'días')})</option>`
  ).join('');
}

async function reportWaste(event) {
  event.preventDefault();
  const batchId = Number($('waste-batch').value);
  if (!batchId) {
    toast('Restaura la tienda demo para ver los lotes.', 'error');
    return;
  }
  const button = $('waste-form').querySelector('button[type="submit"]');
  button.disabled = true;

  try {
    const pending = await request('/inventory/waste', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        batchId,
        quantity: Number($('waste-qty').value),
        reason: $('waste-reason').value
      })
    });
    renderPendingWaste(pending);
    await refreshDashboard();
    toast('Baja declarada. Las unidades quedan en cuarentena hasta que el gerente decida.', 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

function renderPendingWaste(entries) {
  const panel = $('waste-pending');
  const body = $('waste-rows');

  if (!entries.length) {
    panel.classList.add('hidden');
    body.replaceChildren();
    return;
  }
  panel.classList.remove('hidden');

  // Solo el gerente ve los botones; al operario se le explica por que no los tiene.
  const isManager = state.session?.role === 'MANAGER';

  body.replaceChildren(...entries.map((entry) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-3 py-2 font-semibold">${escapeHtml(entry.productName)}</td>
      <td class="px-3 py-2 font-mono text-xs">${escapeHtml(entry.batchCode)}</td>
      <td class="num px-3 py-2">${entry.quantity}</td>
      <td class="num px-3 py-2 text-risk-red">${money(entry.totalLoss)}</td>
      <td class="px-3 py-2">${WASTE_REASONS[entry.reason] ?? escapeHtml(entry.reason)}</td>
      <td class="px-3 py-2 text-ink-soft">${escapeHtml(entry.operatorId)}</td>
      <td class="px-3 py-2">${isManager ? `
        <span class="flex gap-2">
          <button data-waste="${entry.wasteId}" data-decision="approve"
                  class="rounded bg-ink px-3 py-1 text-xs font-semibold text-shell hover:bg-ink-soft">Aprobar</button>
          <button data-waste="${entry.wasteId}" data-decision="reject"
                  class="rounded border border-shell-line px-3 py-1 text-xs font-semibold hover:border-ink">Devolver al stock</button>
        </span>` : '<span class="text-sm text-ink-faint">Requiere al gerente</span>'}</td>`;
    return tr;
  }));
}

$('waste-rows').addEventListener('click', async (event) => {
  const button = event.target.closest('[data-waste]');
  if (!button) return;

  button.disabled = true;
  const decision = button.dataset.decision;
  try {
    const pending = await request(`/inventory/waste/${button.dataset.waste}/${decision}`, { method: 'POST' });
    renderPendingWaste(pending);
    await refreshDashboard();
    toast(decision === 'approve'
      ? 'Merma aprobada: la pérdida queda consolidada.'
      : 'Merma rechazada: la mercadería vuelve al stock vendible.', 'ok');
  } catch (error) {
    toast(error.message, 'error');
    button.disabled = false;
  }
});

async function loadPendingWaste() {
  try {
    renderPendingWaste(await request('/inventory/waste?status=PENDING_APPROVAL'));
  } catch {
    renderPendingWaste([]);
  }
}

/* ─────────────────── panel 3: ingesta de Excel y cuarentena ─────────────────── */

function wireDropzone(zoneId, inputId, onFile) {
  const zone = $(zoneId);
  const input = $(inputId);

  zone.addEventListener('click', () => input.click());
  zone.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      input.click();
    }
  });
  ['dragenter', 'dragover'].forEach((type) => zone.addEventListener(type, (event) => {
    event.preventDefault();
    zone.classList.add('dropzone-active');
  }));
  ['dragleave', 'drop'].forEach((type) => zone.addEventListener(type, (event) => {
    event.preventDefault();
    zone.classList.remove('dropzone-active');
  }));
  zone.addEventListener('drop', (event) => {
    const file = event.dataTransfer?.files?.[0];
    if (file) onFile(file);
  });
  input.addEventListener('change', () => {
    if (input.files?.[0]) onFile(input.files[0]);
  });
}

async function uploadExcel(file) {
  if (!state.token) {
    toast('Primero restaura la tienda demo para obtener acceso.', 'error');
    return;
  }
  const form = new FormData();
  form.append('file', file);

  toast(`Procesando ${file.name}…`);
  try {
    // Sin store_id: la tienda la decide el token, no el navegador.
    const summary = await request('/inventory/ingest/excel', { method: 'POST', body: form });
    $('s-read').textContent = summary.totalRowsProcessed;
    $('s-inserted').textContent = summary.totalInserted;
    $('s-quarantined').textContent = summary.totalQuarantined;
    $('ingest-summary').classList.remove('hidden');
    $('ingest-summary').classList.add('grid');

    state.quarantine = summary.quarantinedRows.map((row) => ({ ...row, values: { ...row.originalValues } }));
    renderQuarantine();
    await refreshDashboard();
    await loadUploadHistory();

    toast(summary.totalQuarantined
      ? `${plural(summary.totalInserted, 'lote creado', 'lotes creados')} y `
        + `${plural(summary.totalQuarantined, 'fila por corregir', 'filas por corregir')}.`
      : `${plural(summary.totalInserted, 'lote creado', 'lotes creados')}. Ninguna fila quedó en cuarentena.`,
      summary.totalQuarantined ? 'info' : 'ok');
  } catch (error) {
    toast(error.message, 'error');
  }
}

const fechaHora = new Intl.DateTimeFormat('es-PE', {
  day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'
});

/** Rastro de auditoria: quien subio cada archivo y que entro con el. */
async function loadUploadHistory() {
  let history;
  try {
    history = await request('/inventory/ingest/history?limit=10');
  } catch {
    return;
  }

  const panel = $('upload-history');
  if (!history.length) {
    panel.classList.add('hidden');
    return;
  }
  panel.classList.remove('hidden');

  $('history-rows').replaceChildren(...history.map((entry) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-3 py-2 font-semibold">${escapeHtml(entry.fileName)}</td>
      <td class="px-3 py-2 text-ink-soft">${escapeHtml(entry.uploadedBy)}</td>
      <td class="num px-3 py-2 text-ink-soft">${fechaHora.format(new Date(entry.createdAt))}</td>
      <td class="num px-3 py-2">${entry.totalRows}</td>
      <td class="num px-3 py-2 text-risk-green">${entry.insertedRows}</td>
      <td class="num px-3 py-2 ${entry.quarantinedRows ? 'text-risk-red' : 'text-ink-faint'}">${entry.quarantinedRows}</td>
      <td class="px-3 py-2 font-mono text-xs text-ink-faint" title="SHA-256 del contenido">${entry.fileHash.slice(0, 12)}…</td>
      <td class="px-3 py-2">${revertAction(entry)}</td>`;
    return tr;
  }));
}

/** Revertir es decision del gerente; al operario se le muestra el estado, sin boton. */
function revertAction(entry) {
  if (entry.rolledBackAt) {
    return '<span class="text-sm text-ink-faint">Revertida</span>';
  }
  if (state.session?.role !== 'MANAGER') {
    return '<span class="text-sm text-ink-faint">—</span>';
  }
  return `<button data-upload="${entry.uploadId}"
                  class="rounded border border-shell-line px-3 py-1 text-xs font-semibold hover:border-ink">
            Revertir
          </button>`;
}

$('history-rows').addEventListener('click', async (event) => {
  const button = event.target.closest('[data-upload]');
  if (!button) return;

  button.disabled = true;
  try {
    const result = await request(`/inventory/ingest/${button.dataset.upload}/rollback`, { method: 'POST' });
    await loadUploadHistory();
    await refreshDashboard();
    toast(result.message, 'ok');
  } catch (error) {
    // El servidor explica que lotes lo impiden; se muestran tal cual.
    toast(error.message, 'error');
    button.disabled = false;
  }
});

/** La descarga pasa por fetch para poder enviar el token en la cabecera. */
async function exportInventory() {
  const button = $('btn-export');
  button.disabled = true;
  try {
    const response = await fetch(`${api}/inventory/export`, { headers: headers() });
    if (!response.ok) {
      throw new Error('No se pudo generar el archivo.');
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `inventario-${new Date().toISOString().slice(0, 10)}.xlsx`;
    link.click();
    URL.revokeObjectURL(url);
    toast('Inventario exportado.', 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

/** Campos editables de la cuarentena, en el orden de la tabla. */
const QUARANTINE_FIELDS = [
  'sku', 'name', 'category', 'unit_cost', 'retail_price', 'batch_code', 'expiration_date', 'initial_qty'
];

function renderQuarantine() {
  const panel = $('quarantine');
  const body = $('quarantine-rows');

  if (!state.quarantine.length) {
    panel.classList.add('hidden');
    body.replaceChildren();
    return;
  }
  panel.classList.remove('hidden');

  body.replaceChildren(...state.quarantine.map((row, index) => {
    const tr = document.createElement('tr');
    tr.className = 'align-top';
    const cells = QUARANTINE_FIELDS.map((field) => `
      <td class="px-3 py-2">
        <span contenteditable="true" data-row="${index}" data-field="${field}"
              class="block min-w-[5rem] rounded px-1 ${fieldIsCulprit(row.errorCode, field)
                ? 'bg-risk-red/10 ring-1 ring-risk-red/40'
                : ''}">${escapeHtml(row.values[field] ?? '')}</span>
      </td>`).join('');

    tr.innerHTML = `
      <td class="num px-3 py-2 text-ink-faint">${row.rowNumber}</td>
      ${cells}
      <td class="px-3 py-2 text-risk-red">${escapeHtml(row.message)}</td>`;
    return tr;
  }));
}

/** Resalta solo la celda que causo el rechazo, no la fila entera. */
function fieldIsCulprit(errorCode, field) {
  const culprit = {
    MISSING_SKU: 'sku',
    MISSING_NAME: 'name',
    MISSING_CATEGORY: 'category',
    INVALID_COST: 'unit_cost',
    INVALID_PRICE: 'retail_price',
    INVALID_QUANTITY: 'initial_qty',
    MISSING_EXPIRATION_DATE: 'expiration_date',
    INVALID_DATE_FORMAT: 'expiration_date',
    EXPIRATION_BEFORE_MANUFACTURING: 'expiration_date'
  }[errorCode];
  return culprit === field;
}

$('quarantine-rows').addEventListener('input', (event) => {
  const cell = event.target.closest('[contenteditable]');
  if (!cell) return;
  state.quarantine[Number(cell.dataset.row)].values[cell.dataset.field] = cell.textContent.trim();
});

async function sendBulkFix() {
  if (!state.quarantine.length) return;
  const button = $('btn-bulkfix');
  button.disabled = true;

  const rows = state.quarantine.map((row) => ({
    rowNumber: row.rowNumber,
    sku: row.values.sku || '',
    barcode: row.values.barcode || null,
    name: row.values.name || '',
    category: row.values.category || '',
    unitOfMeasure: row.values.unit_of_measure || 'UNIDAD',
    unitCost: Number(row.values.unit_cost),
    retailPrice: Number(row.values.retail_price),
    batchCode: row.values.batch_code || 'SIN-LOTE',
    manufacturingDate: row.values.manufacturing_date || null,
    expirationDate: row.values.expiration_date || null,
    initialQty: Number(row.values.initial_qty)
  }));

  try {
    const summary = await request('/inventory/ingest/bulk-fix', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ storeId: state.storeId, rows })
    });

    // Solo sobreviven en pantalla las filas que el servidor volvio a rechazar.
    const rejected = new Set(summary.quarantinedRows.map((row) => row.rowNumber));
    state.quarantine = state.quarantine
      .filter((row) => rejected.has(row.rowNumber))
      .map((row) => {
        const fresh = summary.quarantinedRows.find((item) => item.rowNumber === row.rowNumber);
        return { ...row, errorCode: fresh.errorCode, message: fresh.message };
      });

    renderQuarantine();
    $('s-quarantined').textContent = summary.totalQuarantined;
    await refreshDashboard();

    toast(summary.totalQuarantined
      ? `${plural(summary.totalInserted, 'fila guardada', 'filas guardadas')}. `
        + `Quedan ${summary.totalQuarantined} por corregir.`
      : `${plural(summary.totalInserted, 'fila guardada', 'filas guardadas')}. Cuarentena vacía.`,
      summary.totalQuarantined ? 'info' : 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

/* ─────────────── panel 3: recepcion documental con vision IA ─────────────── */

async function uploadReceipt(file) {
  if (!state.token) {
    toast('Primero restaura la tienda demo para obtener acceso.', 'error');
    return;
  }
  const preview = $('scan-preview');
  preview.src = URL.createObjectURL(file);
  preview.classList.remove('hidden');

  const form = new FormData();
  form.append('file', file);

  toast('Leyendo el documento con visión multimodal…');
  try {
    const scan = await request('/receipts/scan', { method: 'POST', body: form });
    state.scan = scan;
    renderScan(scan);
    toast(scan.unmatchedLines
      ? `Guía leída. ${plural(scan.unmatchedLines, 'línea necesita', 'líneas necesitan')} que elijas el producto.`
      : 'Guía leída y todas las líneas mapeadas.',
      scan.unmatchedLines ? 'info' : 'ok');
  } catch (error) {
    toast(error.message, 'error');
  }
}

function renderScan(scan) {
  $('h-supplier').textContent = scan.supplierName ?? '—';
  $('h-ruc').textContent = scan.supplierTaxId ?? '—';
  $('h-doc').textContent = scan.documentNumber ?? '—';
  $('h-date').textContent = scan.issueDate ?? '—';
  $('h-model').textContent = scan.modelUsed ?? '—';
  $('scan-head').classList.remove('hidden');

  const options = state.catalog.map((product) =>
    `<option value="${product.product_id}">${escapeHtml(product.sku)} · ${escapeHtml(product.name)}</option>`
  ).join('');

  $('scan-rows').replaceChildren(...scan.lines.map((line, index) => {
    const tr = document.createElement('tr');
    const matched = line.productId != null;
    const score = line.matchScore == null ? null : Math.round(line.matchScore * 100);

    tr.innerHTML = `
      <td class="px-3 py-2">
        <span class="font-semibold">${escapeHtml(line.descriptionRaw)}</span>
      </td>
      <td class="px-3 py-2">
        <select data-line="${index}" data-field="productId"
                class="w-56 rounded border px-2 py-1 ${matched ? 'border-shell-line' : 'border-risk-red bg-risk-red/5'}">
          <option value="">Sin asignar</option>
          ${options}
        </select>
      </td>
      <td class="num px-3 py-2 ${scoreTone(score)}">${score == null ? 'sin candidato' : score + '%'}</td>
      <td class="num px-3 py-2">${line.invoicedQty ?? '—'}</td>
      <td class="px-3 py-2">
        <input type="number" min="0" step="1" data-line="${index}" data-field="receivedQty"
               value="${line.invoicedQty ?? 0}"
               class="num w-24 rounded border border-shell-line px-2 py-1 text-right">
      </td>
      <td class="px-3 py-2">
        <input type="text" data-line="${index}" data-field="batchCode" value="${escapeHtml(line.batchCode ?? '')}"
               placeholder="SIN-LOTE" class="w-32 rounded border border-shell-line px-2 py-1 font-mono text-xs">
      </td>
      <td class="px-3 py-2">
        <input type="date" data-line="${index}" data-field="expirationDate" value="${line.expirationDate ?? ''}"
               class="num rounded border border-shell-line px-2 py-1">
      </td>`;

    tr.querySelector('select').value = matched ? String(line.productId) : '';
    return tr;
  }));

  $('scan-table').classList.remove('hidden');
  $('scan-actions').classList.remove('hidden');
  $('scan-actions').classList.add('flex');
  updateScanNote();
}

function scoreTone(score) {
  if (score == null) return 'text-risk-red';
  if (score >= 70) return 'text-risk-green';
  if (score >= 35) return 'text-risk-amber';
  return 'text-risk-red';
}

$('scan-rows').addEventListener('change', (event) => {
  const field = event.target.dataset?.field;
  if (!field) return;
  const line = state.scan.lines[Number(event.target.dataset.line)];
  line[field] = field === 'productId'
    ? (event.target.value ? Number(event.target.value) : null)
    : event.target.value;
  updateScanNote();
});

function updateScanNote() {
  const pending = state.scan.lines.filter((line) => line.productId == null).length;
  $('btn-confirm').disabled = pending > 0;
  $('scan-note').textContent = pending
    ? `Asigna el producto de ${plural(pending, 'línea', 'líneas')} para poder confirmar.`
    : 'Todas las líneas tienen producto asignado.';
}

async function confirmReceipt() {
  const button = $('btn-confirm');
  button.disabled = true;

  const rows = $('scan-rows').querySelectorAll('tr');
  const lines = state.scan.lines.map((line, index) => {
    const row = rows[index];
    const received = Number(row.querySelector('[data-field="receivedQty"]').value || 0);
    const batchCode = row.querySelector('[data-field="batchCode"]').value.trim();
    const expiration = row.querySelector('[data-field="expirationDate"]').value;
    return {
      ocrLineId: line.ocrLineId,
      productId: line.productId,
      invoicedQty: Number(line.invoicedQty ?? 0),
      receivedQty: received,
      unitCost: Number(line.unitCost ?? 0),
      batchCode: batchCode || null,
      manufacturingDate: null,
      expirationDate: expiration || null
    };
  });

  try {
    const result = await request('/receipts/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        storeId: state.storeId,
        supplierTaxId: state.scan.supplierTaxId,
        supplierName: state.scan.supplierName,
        documentNumber: state.scan.documentNumber,
        issueDate: state.scan.issueDate,
        lines
      })
    });
    await refreshDashboard();
    toast(`Recepción ${result.receiptId} registrada: `
      + `${plural(result.batchesCreated, 'lote creado', 'lotes creados')}.`, 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

/* ──────────────────────────── arranque ──────────────────────────── */

$('btn-demo').addEventListener('click', resetDemo);
$('sale-form').addEventListener('submit', registerSale);
$('waste-form').addEventListener('submit', reportWaste);
$('btn-export').addEventListener('click', exportInventory);
$('btn-bulkfix').addEventListener('click', sendBulkFix);
$('btn-confirm').addEventListener('click', confirmReceipt);

wireDropzone('dropzone', 'file-excel', uploadExcel);
wireDropzone('scan-dropzone', 'file-scan', uploadReceipt);

pingBackend();
window.setInterval(pingBackend, 60_000);

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
  salesQuarantine: [], // lo mismo para la hoja de ventas: otros campos, otra tabla
  scan: null,       // ultima guia leida por la IA
  catalog: [],      // productos para el selector de SKU

  // Lo que cada seccion ya trajo del servidor. La pantalla de inicio se arma
  // con esto y no con llamadas propias: es un resumen, no una septima fuente de
  // verdad que pueda contradecir a las demas.
  dashboard: null,
  alerts: null,
  fefoQueue: [],
  batches: [],
  pendingWaste: [],
  wasteHistory: [],
  markdowns: [],
  uploads: [],
  quota: null,
  riskSeries: [],
  categories: [],
  // Las familias que el usuario quito del grafico. Se guarda lo excluido y no
  // lo incluido: asi una categoria nueva aparece sola la proxima vez, en vez
  // de quedarse invisible porque no estaba en la lista cuando se filtro.
  categoriesOff: new Set(),
  memberships: [],
  returns: [],
  returnDraft: null
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
    info: 'bg-ink text-white',
    ok: 'bg-risk-green text-white',
    error: 'bg-risk-red text-white'
  };
  const node = document.createElement('p');
  node.className = `pointer-events-auto max-w-md rounded-lg px-4 py-3 text-sm font-medium shadow-lift ${colors[tone]}`;
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

const TABS = ['inicio', 'semaforo', 'alertas', 'inventario', 'excel', 'recepcion', 'analisis', 'diagnostico'];

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
    // El resto del aspecto de la pestaña activa lo lleva el CSS, atado a
    // aria-selected: el estado visual no puede depender de que alguien se
    // acuerde de añadir una clase aquí.
    $(`tab-${tab}`).classList.toggle('text-white', selected);
    $(`panel-${tab}`).classList.toggle('hidden', !selected);
  });

  // La pantalla de inicio se repinta con lo ya cargado, sin pedir nada: es un
  // resumen de lo que las demas secciones ya trajeron.
  if (name === 'inicio') renderToday();

  // El inventario se recarga al entrar: pudo cambiar por una venta o una merma.
  if (name === 'inventario' && state.token) {
    loadBatches();
    loadWasteHistory();
    loadReturns();
  }
  if (name === 'alertas' && state.token) {
    loadAlerts();
    loadMarkdownHistory();
  }
  if (name === 'recepcion' && state.token) {
    loadQuota();
  }
  if (name === 'analisis' && state.token) {
    loadInsights();
    loadRiskHistory();
  }
  if (name === 'diagnostico' && state.token) {
    loadDiagnostics();
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

/* ──────────────────── panel 0: lo primero de hoy ────────────────────
 *
 * Esta pantalla no consulta nada: se arma con lo que las demas secciones ya
 * trajeron (state.dashboard, state.alerts, state.batches…). Es deliberado. Un
 * resumen con llamadas propias acabaria contando una cifra distinta de la que
 * muestra la seccion de al lado, y entonces no se puede confiar en ninguna.
 */

const fechaLarga = new Intl.DateTimeFormat('es-PE', {
  weekday: 'long', day: 'numeric', month: 'long'
});

/** Las tres cosas que mueven dinero, cada una con su icono de trazo. */
const ACTIVITY_ICONS = {
  carga: '<path d="M12 16V4m0 0L8 8m4-4 4 4"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
  rebaja: '<path d="M7 10h10M7 14h6"/><path d="M4 5h16v14l-3-2H4z"/>',
  baja: '<path d="M4 7h16M9 7V5h6v2m-8 0 1 13h8l1-13"/>'
};

/**
 * Lo que hay que mirar hoy, en orden de lo que cuesta no mirarlo.
 *
 * El orden no es decorativo: arriba va lo que ya es perdida (un lote vencido
 * con stock), luego lo que lo sera en horas, y al final lo que solo es
 * conveniente. Una lista por fecha de creacion no serviria para nada.
 */
function todayPriorities() {
  const items = [];
  const dash = state.dashboard;

  // Una tienda recien creada no esta «al dia»: esta vacia, que no es lo mismo.
  // Sin esto, lo primero que ve quien acaba de dar de alta su empresa es que no
  // tiene nada que hacer, justo cuando tiene que hacerlo todo.
  if (dash && dash.activeBatches === 0 && !state.uploads.length && !state.batches.length) {
    return [{
      tone: 'neutral', chip: 'Primer paso',
      title: 'Tu tienda todavía no tiene inventario',
      detail: 'Sube tu Excel de existencias o recibe una guía de remisión con la cámara. '
            + 'A partir de ahí Kardo empieza a avisar de lo que vence.',
      goto: 'excel', cta: 'Cargar Excel'
    }];
  }

  const vencidos = state.batches.filter((b) => b.daysToExpiry < 0 && b.currentQty > 0);
  if (vencidos.length) {
    const costo = vencidos.reduce((sum, b) => sum + Number(b.value ?? 0), 0);
    items.push({
      tone: 'expired', chip: plural(vencidos.length, 'lote', 'lotes'),
      title: 'Mercadería vencida que sigue en el anaquel',
      detail: `${money(costo)} que ya no se puede vender. Dar de baja antes de que se venda por error.`,
      goto: 'inventario', cta: 'Dar de baja'
    });
  }

  if (dash?.redAlertBatches > 0) {
    items.push({
      tone: 'red', chip: plural(dash.redAlertBatches, 'lote', 'lotes'),
      title: 'Vencen en menos de 48 horas',
      detail: 'Es la última ventana para rebajar o mover la mercadería con algo de margen.',
      goto: 'semaforo', cta: 'Ver la cola'
    });
  }

  const alertas = state.alerts?.alerts?.length ?? 0;
  if (alertas) {
    // La primera rebaja aplicable se puede resolver sin salir de aquí: llevar
    // al operario a otra pantalla para pulsar un botón que cabe en esta linea
    // es trabajo que le cobramos sin motivo.
    const primera = state.alerts.alerts.find(
      (alerta) => alerta.action === 'REBAJAR' && alerta.suggestedPrice !== null);

    items.push({
      tone: 'red', chip: plural(alertas, 'decisión', 'decisiones'),
      title: 'Rebajas sugeridas sin resolver',
      detail: primera
        ? `La primera: ${primera.productName} a ${money(primera.suggestedPrice)} (−${
            primera.discountPercent}%). En total, ${money(state.alerts.valueAtRisk)} de costo en riesgo.`
        : `${money(state.alerts.valueAtRisk)} de costo en riesgo frente a ${
            money(state.alerts.marginSacrificed)} de margen que cuesta rebajar.`,
      goto: 'alertas', cta: 'Ver todas',
      quick: primera
        ? { batchId: primera.batchId, label: `Rebajar a ${money(primera.suggestedPrice)}` }
        : null
    });
  }

  if (state.pendingWaste.length) {
    const esGerente = state.session?.role === 'MANAGER';
    const perdida = state.pendingWaste.reduce((sum, e) => sum + Number(e.totalLoss ?? 0), 0);
    items.push({
      tone: 'amber', chip: plural(state.pendingWaste.length, 'baja', 'bajas'),
      title: esGerente ? 'Bajas esperando tu decisión' : 'Bajas esperando al gerente',
      detail: `${money(perdida)} inmovilizados. ${esGerente
        ? 'Consolidar la pérdida o devolver la mercadería al stock.'
        : 'Las unidades no se venden hasta que alguien resuelva.'}`,
      goto: 'inventario', cta: esGerente ? 'Resolver' : 'Ver'
    });
  }

  if (state.quarantine.length) {
    items.push({
      tone: 'amber', chip: plural(state.quarantine.length, 'fila', 'filas'),
      title: 'Filas de la última carga sin corregir',
      detail: 'No entraron al inventario. Se arreglan en la propia tabla, sin volver a subir el archivo.',
      goto: 'excel', cta: 'Corregir'
    });
  }

  if (dash?.linesWithDiscrepancy > 0) {
    items.push({
      tone: 'amber', chip: plural(dash.linesWithDiscrepancy, 'línea', 'líneas'),
      title: 'Guías con lo facturado distinto de lo recibido',
      detail: 'Un descuadre que no se reclama al proveedor lo paga la tienda.',
      goto: 'recepcion', cta: 'Revisar'
    });
  }

  if (dash?.yellowAlertBatches > 0) {
    items.push({
      tone: 'amber', chip: plural(dash.yellowAlertBatches, 'lote', 'lotes'),
      title: 'Vencen esta semana',
      detail: 'Todavía se venden a precio de lista; es el momento más barato para actuar.',
      goto: 'semaforo', cta: 'Ver la cola'
    });
  }

  if (state.quota && state.quota.remainingToday <= 3) {
    items.push({
      tone: 'neutral', chip: `${state.quota.remainingToday} escaneos`,
      title: 'Queda poco cupo de lectura de guías',
      detail: 'La visión multimodal tiene un tope diario por tienda; se renueva a medianoche.',
      goto: 'recepcion', cta: 'Ver cupo'
    });
  }

  return items;
}

const PRIORITY_CHIPS = {
  expired: 'chip-expired', red: 'chip-red', amber: 'chip-amber', neutral: 'chip-neutral'
};

function priorityItem(item) {
  const li = document.createElement('li');
  // En el movil el boton baja a su propia linea en vez de estrujar el titulo
  // hasta una palabra por renglon: `basis-48` es el ancho por debajo del cual
  // el texto deja de ser legible y prefiere envolver.
  li.className = 'flex flex-wrap items-start gap-x-4 gap-y-3 px-5 py-4';
  li.innerHTML = `
    <span class="shrink-0 pt-0.5 sm:w-24"><span class="chip ${PRIORITY_CHIPS[item.tone]}">${item.chip}</span></span>
    <span class="min-w-0 flex-1 basis-48">
      <span class="block font-semibold">${escapeHtml(item.title)}</span>
      <span class="block text-sm text-ink-soft">${escapeHtml(item.detail)}</span>
    </span>
    <span class="ml-auto flex shrink-0 gap-2">
      ${item.quick ? `
        <button type="button" data-quick="${item.quick.batchId}"
                class="rounded-md bg-brand px-3 py-1.5 text-sm font-semibold text-white
                       transition-colors hover:bg-brand-deep disabled:opacity-60">
          ${escapeHtml(item.quick.label)}
        </button>` : ''}
      <button type="button" data-goto="${item.goto}"
              class="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-semibold
                     text-ink transition-colors hover:border-brand hover:text-brand">
        ${escapeHtml(item.cta)}
      </button>
    </span>`;
  return li;
}

/** Reparto de los lotes con existencias por lo que les queda de vida. */
function expiryBuckets() {
  const buckets = [
    { key: 'expired', label: 'Vencidos, con stock', bar: 'bg-ink', dot: 'bg-ink', count: 0, value: 0 },
    { key: 'red', label: 'Vencen en 48 h', bar: 'bg-risk-red', dot: 'bg-risk-red', count: 0, value: 0 },
    { key: 'amber', label: 'Vencen en 5 días', bar: 'bg-risk-amber', dot: 'bg-risk-amber', count: 0, value: 0 },
    { key: 'green', label: 'Con holgura', bar: 'bg-risk-green', dot: 'bg-risk-green', count: 0, value: 0 }
  ];
  const index = { expired: 0, red: 1, amber: 2, green: 3 };

  state.batches.filter((b) => b.currentQty > 0).forEach((batch) => {
    const bucket = buckets[index[batchState(batch.daysToExpiry).key]];
    bucket.count += 1;
    bucket.value += Number(batch.value ?? 0);
  });
  return buckets;
}

function renderExpiryBar() {
  const buckets = expiryBuckets();
  const total = buckets.reduce((sum, b) => sum + b.count, 0);
  const bar = $('expiry-bar');
  const legend = $('expiry-legend');

  if (!total) {
    bar.replaceChildren();
    legend.innerHTML = '<p class="py-2 text-ink-faint">Sin lotes con existencias.</p>';
    return;
  }

  bar.replaceChildren(...buckets.filter((b) => b.count).map((b) => {
    const span = document.createElement('span');
    span.className = b.bar;
    span.style.width = `${(b.count / total) * 100}%`;
    span.title = `${b.label}: ${b.count}`;
    return span;
  }));

  legend.replaceChildren(...buckets.map((b) => {
    const row = document.createElement('div');
    row.className = `flex items-baseline justify-between gap-4 ${b.count ? '' : 'text-ink-faint'}`;
    row.innerHTML = `
      <dt class="flex items-center gap-2">
        <span class="h-2 w-2 shrink-0 rounded-full ${b.dot}" aria-hidden="true"></span>
        ${b.label}
      </dt>
      <dd class="num shrink-0">
        <span class="font-semibold">${b.count}</span>
        <span class="text-ink-faint">· ${money(b.value)}</span>
      </dd>`;
    return row;
  }));
}

/**
 * Las tres historias en una sola linea de tiempo.
 *
 * Cada una vive en su seccion, y ahi es donde se trabaja; juntas y en orden son
 * lo que responde «que ha pasado en la tienda desde ayer».
 */
function recentActivity() {
  const eventos = [];

  state.uploads.forEach((entry) => eventos.push({
    kind: 'carga', at: new Date(entry.createdAt), who: entry.uploadedBy,
    text: `Carga de <span class="font-mono text-xs">${escapeHtml(entry.fileName)}</span>: ${
      plural(entry.insertedRows, 'lote creado', 'lotes creados')}${
      entry.quarantinedRows ? `, ${entry.quarantinedRows} en cuarentena` : ''}`
  }));

  state.markdowns.forEach((row) => eventos.push({
    kind: 'rebaja', at: new Date(row.decided_at), who: row.decided_by,
    text: row.decision === 'APPLIED'
      ? `<span class="font-semibold">${escapeHtml(row.product_name)}</span> rebajado a ${
          money(row.suggested_price)} (peldaño ${row.step_percent}%)`
      : `Rebaja descartada en <span class="font-semibold">${escapeHtml(row.product_name)}</span>`
  }));

  state.wasteHistory.forEach((entry) => eventos.push({
    kind: 'baja', at: new Date(entry.loggedAt), who: entry.authorizedBy || entry.operatorId,
    text: `<span class="font-semibold">${escapeHtml(entry.productName)}</span>: baja de ${
      entry.quantity} u · ${(WASTE_STATUS[entry.status] ?? { label: entry.status }).label.toLowerCase()}`
  }));

  return eventos.sort((a, b) => b.at - a.at).slice(0, 7);
}

/** Las cinco primeras de la cola FEFO, que es lo que cabe antes de despachar. */
function renderTodayQueue() {
  const list = $('today-queue');
  const rows = state.fefoQueue.slice(0, 5);

  if (!rows.length) {
    list.innerHTML = '<li class="px-5 py-8 text-center text-sm text-ink-faint">No hay lotes activos con stock.</li>';
    return;
  }

  list.replaceChildren(...rows.map((row, index) => {
    const li = document.createElement('li');
    li.className = 'flex items-center gap-x-3 px-4 py-2.5 sm:px-5';
    li.innerHTML = `
      <span class="num w-5 shrink-0 text-xs text-ink-faint">${index + 1}</span>
      <span class="shrink-0 sm:w-16">${stateChip(Number(row.dias_restantes))}</span>
      <span class="min-w-0 flex-1">
        <span class="block truncate font-medium">${escapeHtml(row.name)}</span>
        <span class="num block text-xs text-ink-faint sm:hidden">${row.current_qty} u</span>
      </span>
      <span class="hidden w-40 shrink-0 truncate text-right font-mono text-xs text-ink-faint lg:inline">${escapeHtml(row.batch_code)}</span>
      <span class="num hidden w-16 shrink-0 text-right text-sm text-ink-soft sm:inline">${row.current_qty} u</span>
      <span class="num w-20 shrink-0 text-right text-sm font-semibold sm:w-24">${money(row.valor)}</span>`;
    return li;
  }));
}

$('btn-queue-all').addEventListener('click', () => {
  showTab('semaforo');
  $('tab-semaforo').focus();
});

function renderActivity() {
  const list = $('activity-list');
  const eventos = recentActivity();

  if (!eventos.length) {
    // Decir «nada» y callarse deja la mitad de la pantalla muerta sin explicar
    // qué llegará a aparecer aquí.
    list.innerHTML = `
      <li class="py-3 text-ink-soft">
        Todavía no se ha movido nada. Aquí quedará registrado, con autor y hora:
      </li>
      <li class="py-2 text-ink-faint">· cada carga de Excel y los lotes que creó</li>
      <li class="py-2 text-ink-faint">· cada rebaja decidida y a qué precio</li>
      <li class="py-2 text-ink-faint">· cada baja declarada y quién la resolvió</li>`;
    return;
  }

  list.replaceChildren(...eventos.map((evento) => {
    const li = document.createElement('li');
    li.className = 'flex items-start gap-3 py-3';
    li.innerHTML = `
      <span class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-brand-tint">
        <svg class="h-3.5 w-3.5 text-brand-deep" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          ${ACTIVITY_ICONS[evento.kind]}
        </svg>
      </span>
      <span class="min-w-0 flex-1">
        <span class="block">${evento.text}</span>
        <span class="num block text-xs text-ink-faint">${
          fechaHora.format(evento.at)} · ${escapeHtml(evento.who ?? '—')}</span>
      </span>`;
    return li;
  }));
}

function renderToday() {
  $('today-date').textContent = fechaLarga.format(new Date());

  const abierta = Boolean(state.token);
  $('today-onboarding').classList.toggle('hidden', abierta);
  $('today-body').classList.toggle('hidden', !abierta);
  if (!abierta) {
    $('today-badge').classList.add('hidden');
    return;
  }

  const dash = state.dashboard;
  $('t-risk').textContent = money(dash?.valueAtRisk);
  $('t-risk-note').textContent = dash
    ? `${plural(dash.redAlertBatches, 'lote', 'lotes')} en 48 h · ${
        plural(dash.yellowAlertBatches, 'lote', 'lotes')} en 5 días`
    : '—';
  $('t-decisions').textContent = state.alerts?.alerts?.length ?? '—';
  $('t-inventory').textContent = money(dash?.inventoryValue);
  $('t-active').textContent = plural(dash?.activeBatches ?? 0, 'lote activo', 'lotes activos');
  $('t-loss').textContent = money(state.wasteHistory
    .filter((entry) => entry.status === 'APPROVED')
    .reduce((sum, entry) => sum + Number(entry.totalLoss ?? 0), 0));

  const items = todayPriorities();
  const list = $('priority-list');
  const badge = $('today-badge');

  badge.textContent = items.length;
  badge.classList.toggle('hidden', items.length === 0);
  $('priority-count').textContent = items.length
    ? plural(items.length, 'cosa por mirar', 'cosas por mirar')
    : 'todo resuelto';

  if (!items.length) {
    // Un panel vacio porque esta todo resuelto no dice lo mismo que uno vacio
    // porque nadie ha cargado nada: aqui se sabe cual de los dos es.
    list.innerHTML = `
      <li class="flex items-start gap-3 px-5 py-8">
        <span class="chip chip-green">Al día</span>
        <span class="text-sm text-ink-soft">
          Nada vence en los próximos días, no hay bajas pendientes y la última carga entró limpia.
        </span>
      </li>`;
  } else {
    list.replaceChildren(...items.map(priorityItem));
  }

  renderTodayTrend();
  renderExpiryBar();
  renderActivity();
  renderTodayQueue();
}

// Un clic en la prioridad abre la seccion donde se resuelve: la lista no es un
// informe, es el indice de lo que hay que hacer.
$('priority-list').addEventListener('click', async (event) => {
  // Aplicar la rebaja aqui usa el mismo endpoint que el panel de alertas; lo
  // que cambia es donde esta el boton, no lo que queda firmado en los libros.
  const rebajar = event.target.closest('[data-quick]');
  if (rebajar) {
    rebajar.disabled = true;
    try {
      const board = await request(`/inventory/alerts/${rebajar.dataset.quick}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ decision: 'APPLIED' })
      });
      renderAlerts(board);
      await loadMarkdownHistory();
      await loadBatches();
      toast('Rebaja aplicada. El lote ya se vende a ese precio.', 'ok');
    } catch (error) {
      rebajar.disabled = false;
      toast(error.message, 'error');
    }
    return;
  }

  const destino = event.target.closest('[data-goto]');
  if (!destino) return;
  showTab(destino.dataset.goto);
  $(`tab-${destino.dataset.goto}`).focus();
});

// Delegado en el contenedor y no chip a chip: renderCategories reemplaza los
// botones en cada pintada, y suscribirse a cada uno dejaria oyentes colgando
// de nodos que ya no estan en el documento.
$('category-filters').addEventListener('click', (event) => {
  const chip = event.target.closest('[data-categoria]');
  if (!chip) return;

  const nombre = chip.dataset.categoria;
  const activa = state.categoriesOff.has(nombre);
  if (activa) {
    state.categoriesOff.delete(nombre);
  } else {
    state.categoriesOff.add(nombre);
  }

  vestirChip(chip, activa);
  renderCategoryBars();
});

$('btn-today-refresh').addEventListener('click', () => {
  if (!state.token) {
    toast('Restaura la tienda demo para tener algo que mirar.');
    return;
  }
  refreshDashboard();
  loadBatches();
  loadPendingWaste();
  loadWasteHistory();
  loadMarkdownHistory();
  loadUploadHistory();
  loadRiskHistory();
});

/* ──────────────────────── panel 1: semaforo FEFO ──────────────────────── */

function renderDashboard(dashboard) {
  state.dashboard = dashboard;
  renderToday();
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
  if (days < 0) return { key: 'expired', label: 'Vencido', chip: 'chip-expired', tone: 'text-ink' };
  if (days <= 2) {
    return { key: 'red', label: days === 0 ? 'Hoy' : `${days} d`, chip: 'chip-red', tone: 'text-risk-red' };
  }
  if (days <= 5) return { key: 'amber', label: `${days} d`, chip: 'chip-amber', tone: 'text-risk-amber' };
  return { key: 'green', label: `${days} d`, chip: 'chip-neutral', tone: 'text-ink-soft' };
}

/**
 * El estado como etiqueta y no como filete de color.
 *
 * Un filete lateral obliga a saber de antemano qué significa cada color. El
 * chip lleva el dato dentro —"2 d", "Vencido"—, así que el color refuerza pero
 * nunca comunica solo.
 */
function stateChip(days) {
  const state_ = batchState(days);
  return `<span class="chip ${state_.chip}">${state_.label}</span>`;
}

function renderFefoQueue(rows) {
  const list = $('fefo-queue');
  state.fefoQueue = rows;
  renderToday();
  if (!rows.length) {
    list.innerHTML =
      '<li class="px-5 py-14 text-center text-sm text-ink-faint">No hay lotes activos con stock.</li>';
    return;
  }
  list.replaceChildren(...rows.map((row, index) => {
    const days = Number(row.dias_restantes);
    const item = document.createElement('li');
    // En pantallas estrechas se ocultan lote y fecha: lo que no puede faltar es
    // cuanto le queda al lote, que producto es y cuanto dinero hay en juego.
    item.className = 'flex items-center gap-x-3 px-4 py-3 sm:px-5';
    item.innerHTML = `
      <span class="num w-5 shrink-0 text-xs text-ink-faint">${index + 1}</span>
      <span class="shrink-0 sm:w-16">${stateChip(days)}</span>
      <span class="min-w-0 flex-1" title="${escapeHtml(row.name)}">
        <span class="block truncate font-medium">${escapeHtml(row.name)}</span>
        <span class="num block text-xs text-ink-faint sm:hidden">${row.current_qty} u</span>
      </span>
      <span class="hidden font-mono text-xs text-ink-faint lg:inline">${escapeHtml(row.batch_code)}</span>
      <span class="num hidden w-16 shrink-0 text-right text-sm text-ink-soft sm:inline">${row.current_qty} u</span>
      <span class="num w-20 shrink-0 text-right text-sm font-semibold sm:w-24">${money(row.valor)}</span>
      <span class="num hidden w-24 shrink-0 text-right text-sm text-ink-faint lg:inline">${row.expiration_date}</span>`;
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

  // Una venta o una merma cambian lo que conviene rebajar, y el contador de la
  // pestaña debe decirlo aunque el operario este en otra pantalla.
  await loadAlerts();
}

/* ───────────────────────── restaurar la demo ───────────────────────── */

async function resetDemo() {
  const button = $('btn-demo');
  button.disabled = true;
  // Los tres caminos al mismo sitio se bloquean juntos; uno de ellos activo
  // mientras los otros giran invita a sembrar la demo dos veces.
  $('btn-demo-home').disabled = true;
  $('btn-demo-home').textContent = 'Restaurando…';
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
    await loadQuota();
    await loadAlerts();
    await loadMarkdownHistory();
    await loadPendingWaste();
    await loadWasteHistory();
    await loadUploadHistory();
    await loadBatches();
    await loadReturns();
    await loadRiskHistory();
    toast('Tienda demo restaurada. Ya puedes cargar inventario o una guía.', 'ok');
  } catch (error) {
    toast(`No se pudo restaurar la demo: ${error.message}`, 'error');
  } finally {
    button.disabled = false;
    $('btn-demo-home').disabled = false;
    $('btn-demo-home').textContent = 'Restaurar tienda demo';
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
  const rol = roles[session.role] ?? session.role;

  $('session').classList.remove('hidden');
  // Con sesion abierta el boton deja de invitar a entrar y pasa a ofrecer el
  // cambio de persona, que es lo que hace falta para recorrer el circuito de
  // mermas: quien declara no puede resolver.
  $('btn-switch').textContent = 'Cambiar de usuario';
  $('session-user').textContent = session.email;
  $('session-role').textContent = `${rol} · ${session.storeName}`;

  // En movil la barra lateral esta colapsada y la sesion vive arriba.
  $('session-mobile').classList.remove('hidden');
  $('session-mobile').textContent = `${session.email} · ${rol}`;
  $('btn-switch-mobile').textContent = 'Cambiar';

  // Abrir tiendas y dar de alta a gente es decidir quien entra al sistema, de
  // modo que el boton ni siquiera existe para el operario. El rol es el de
  // ESTA tienda: la misma persona puede verlo aqui y no verlo en la de al lado.
  $('btn-org').classList.toggle('hidden', session.role !== 'MANAGER');

  // Las tiendas en las que trabaja no cambian al moverse entre ellas, pero si
  // al entrar con otra persona.
  loadMyStores();

  // Con sesion abierta la pantalla de inicio deja de ser una invitacion y pasa
  // a ser el resumen del dia.
  renderToday();
}

/* ───────────────────────── cambio de usuario ───────────────────────── */

/**
 * Permite entrar con otra cuenta sin recargar.
 *
 * <p>Existe porque la aprobacion de mermas exige que quien resuelve no sea quien
 * declaro: para recorrer ese circuito hace falta poder ser las dos personas.
 */
async function switchUser(event) {
  event.preventDefault();
  const error = $('login-error');
  error.classList.add('hidden');

  try {
    const session = await request('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: $('login-email').value.trim(), password: $('login-password').value })
    });

    applySession(session);
    $('login-dialog').close();
    $('login-password').value = '';

    // Todo lo que se ve depende de quien mira: roles, botones y tienda.
    await Promise.all([loadCatalog(), loadPendingWaste(), loadUploadHistory(), loadBatches(),
                       loadMarkdownHistory(), loadQuota(), loadWasteHistory(), loadRiskHistory(), loadReturns()]);
    await refreshDashboard();
    toast(`Sesión de ${session.fullName} (${session.role === 'MANAGER' ? 'gerente' : 'operario'}).`, 'ok');
  } catch (failure) {
    error.textContent = failure.message;
    error.classList.remove('hidden');
  }
}

$('btn-switch').addEventListener('click', () => {
  $('login-email').value = state.session?.email === 'demo@kardo.pe' ? 'operario@kardo.pe' : 'demo@kardo.pe';
  $('login-password').value = '';
  $('login-error').classList.add('hidden');
  $('login-dialog').showModal();
});
$('login-cancel').addEventListener('click', () => $('login-dialog').close());
$('login-form').addEventListener('submit', switchUser);

/* ─────────────────────── cambio de tienda ───────────────────────
 *
 * Una persona puede trabajar en varias tiendas con un rol distinto en cada una
 * (sql/13). Cambiar de tienda no es filtrar la pantalla: se pide un token nuevo
 * con esa tienda y ese rol, y se recarga todo, porque todo lo que se ve depende
 * de la tienda del token.
 */

function renderStoreSwitcher(memberships) {
  const varias = memberships.length > 1;

  [$('store-switcher'), $('store-switcher-mobile')].forEach((select) => {
    select.classList.toggle('hidden', !varias);
    if (!varias) return;

    select.replaceChildren(...memberships.map((membership) => {
      const option = document.createElement('option');
      option.value = membership.storeId;
      // El rol va en la etiqueta porque cambia con la tienda: quien elige
      // tiene que saber que deja de ser gerente al moverse.
      option.textContent = `${membership.storeName} · ${
        membership.role === 'MANAGER' ? 'gerente' : 'operario'}`;
      option.selected = membership.storeId === state.session?.storeId;
      return option;
    }));
  });

  // Con varias tiendas, el selector ya dice quien eres y donde estas.
  $('session-mobile').classList.toggle('hidden', varias || !state.token);
}

async function loadMyStores() {
  try {
    state.memberships = await request('/auth/stores');
  } catch {
    state.memberships = [];
  }
  renderStoreSwitcher(state.memberships);
}

async function switchStore(storeId) {
  try {
    const session = await request('/auth/store', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ storeId: Number(storeId) })
    });
    applySession(session);

    // Todo lo cargado es de la tienda anterior: no se filtra, se vuelve a pedir.
    await Promise.all([loadCatalog(), loadBatches(), loadAlerts(), loadMarkdownHistory(),
                       loadPendingWaste(), loadWasteHistory(), loadUploadHistory(),
                       loadQuota(), loadRiskHistory()]);
    await refreshDashboard();
    toast(`Ahora operas ${session.storeName} como ${
      session.role === 'MANAGER' ? 'gerente' : 'operario'}.`, 'ok');
  } catch (error) {
    toast(error.message, 'error');
    // El selector se quedo donde no debia: se devuelve a la tienda real.
    renderStoreSwitcher(state.memberships);
  }
}

$('store-switcher').addEventListener('change', (event) => switchStore(event.target.value));
$('store-switcher-mobile').addEventListener('change', (event) => switchStore(event.target.value));

/* ───────────── alta de empresa y estructura de la organizacion ─────────────
 *
 * Hasta aqui una empresa solo nacia del boton de restaurar la demo. Esto es la
 * puerta por la que se sale de ella: creas tu empresa, tu primera tienda y tu
 * cuenta, y entras con el inventario vacio.
 */

/** Deja el formulario y el boton como estaban, pase lo que pase. */
function resetForm(formId, submitButton, label) {
  $(formId).reset();
  submitButton.disabled = false;
  submitButton.textContent = label;
}

async function registerCompany(event) {
  event.preventDefault();
  const error = $('register-error');
  const button = $('register-submit');

  error.classList.add('hidden');
  button.disabled = true;
  button.textContent = 'Creando…';

  try {
    const session = await request('/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        companyName: $('reg-company').value.trim(),
        taxId: $('reg-ruc').value.trim(),
        storeName: $('reg-store').value.trim(),
        storeAddress: $('reg-address').value.trim(),
        fullName: $('reg-name').value.trim(),
        email: $('reg-email').value.trim(),
        password: $('reg-password').value
      })
    });

    applySession(session);
    $('register-dialog').close();
    $('login-dialog').close();
    resetForm('register-form', button, 'Crear empresa y entrar');

    // Una tienda recien creada no tiene nada; aun asi se carga todo, porque lo
    // que el gerente tiene que ver es el estado vacio de cada seccion, que es
    // el que le dice por donde empezar.
    await Promise.all([loadCatalog(), loadBatches(), loadAlerts(), loadMarkdownHistory(),
                       loadWasteHistory(), loadUploadHistory(), loadQuota(), loadRiskHistory()]);
    await refreshDashboard();
    showTab('inicio');

    toast(`Empresa creada. Estás en ${session.storeName}, con el inventario vacío.`, 'ok');
  } catch (failure) {
    error.textContent = failure.message;
    error.classList.remove('hidden');
    button.disabled = false;
    button.textContent = 'Crear empresa y entrar';
  }
}

function renderOrgStores(stores) {
  const list = $('org-stores');
  const select = $('org-user-store');

  list.replaceChildren(...stores.map((store) => {
    const li = document.createElement('li');
    li.className = 'flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5';
    li.innerHTML = `
      <span class="min-w-0">
        <span class="font-medium">${escapeHtml(store.name)}</span>
        <span class="font-mono text-xs text-ink-faint">${escapeHtml(store.code)}</span>
        ${store.address ? `<span class="block text-xs text-ink-soft">${escapeHtml(store.address)}</span>` : ''}
      </span>
      <span class="num shrink-0 text-sm text-ink-soft">${plural(store.users, 'persona', 'personas')}</span>`;
    return li;
  }));

  select.replaceChildren(...stores.map((store) => {
    const option = document.createElement('option');
    option.value = store.storeId;
    option.textContent = store.name;
    return option;
  }));
}

async function loadStores() {
  try {
    renderOrgStores(await request('/stores'));
  } catch (error) {
    $('org-error').textContent = error.message;
    $('org-error').classList.remove('hidden');
  }
}

async function createStore(event) {
  event.preventDefault();
  const button = event.submitter;
  const error = $('org-error');

  error.classList.add('hidden');
  button.disabled = true;

  try {
    await request('/stores', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: $('org-store-name').value.trim(),
        address: $('org-store-address').value.trim()
      })
    });
    resetForm('org-store-form', button, 'Abrir tienda');
    await loadStores();
    toast('Tienda abierta. Ya puedes dar de alta a quien trabaje en ella.', 'ok');
  } catch (failure) {
    error.textContent = failure.message;
    error.classList.remove('hidden');
    button.disabled = false;
  }
}

async function createUser(event) {
  event.preventDefault();
  const button = event.submitter;
  const error = $('org-error');
  const email = $('org-user-email').value.trim();

  error.classList.add('hidden');
  button.disabled = true;

  try {
    await request('/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fullName: $('org-user-name').value.trim(),
        email,
        password: $('org-user-password').value,
        role: $('org-user-role').value,
        storeId: Number($('org-user-store').value)
      })
    });
    resetForm('org-user-form', button, 'Dar de alta');
    await loadStores();
    // Puede haberse añadido a si mismo a la tienda nueva: sin esto, el selector
    // no aparece hasta que vuelva a entrar.
    await loadMyStores();
    // Se repite el correo y no la contraseña: el correo es lo que hay que
    // comunicarle, y la contraseña ya la sabe quien la acaba de escribir.
    toast(`${email} ya puede entrar.`, 'ok');
  } catch (failure) {
    error.textContent = failure.message;
    error.classList.remove('hidden');
    button.disabled = false;
  }
}

$('btn-register').addEventListener('click', () => {
  $('register-error').classList.add('hidden');
  $('register-dialog').showModal();
  $('reg-company').focus();
});
$('register-cancel').addEventListener('click', () => $('register-dialog').close());
$('register-form').addEventListener('submit', registerCompany);

$('btn-org').addEventListener('click', async () => {
  $('org-error').classList.add('hidden');
  $('org-company').textContent = `${state.session?.email ?? ''} · gerente`;
  $('org-dialog').showModal();
  await loadStores();
});
$('org-close').addEventListener('click', () => $('org-dialog').close());
$('org-store-form').addEventListener('submit', createStore);
$('org-user-form').addEventListener('submit', createUser);

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
  $('sale-result').classList.add('hidden');

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
    $('sale-result').classList.remove('hidden');

    await refreshDashboard();
    toast(`Salida registrada: ${plural(quantity, 'unidad', 'unidades')} de ${sale.productName}.`, 'ok');
  } catch (error) {
    $('sale-result').classList.add('hidden');
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

  // El reparto de vencimientos de la pantalla de inicio necesita el inventario
  // completo; una lista ya filtrada por el buscador lo contaria mal.
  if (!query && $('batch-filter').value === 'TODOS') {
    state.batches = batches;
    renderToday();
  }

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
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-4 py-2.5">${stateChip(batch.daysToExpiry)}</td>
      <td class="px-4 py-2.5 font-medium">${escapeHtml(batch.productName)}</td>
      <td class="px-4 py-2.5 font-mono text-xs text-ink-soft">${escapeHtml(batch.sku)}</td>
      <td class="px-4 py-2.5 font-mono text-xs text-ink-soft">${escapeHtml(batch.batchCode)}</td>
      <td class="num px-4 py-2.5 text-right">${batch.currentQty}</td>
      <td class="num px-4 py-2.5 text-right ${batch.quarantinedQty ? 'text-risk-amber font-semibold' : 'text-ink-faint'}">${batch.quarantinedQty}</td>
      <td class="num px-4 py-2.5 text-right font-semibold">${money(batch.value)}</td>
      <td class="px-4 py-2.5 text-xs text-ink-soft">${escapeHtml(batch.status)}</td>`;
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
    await loadWasteHistory();
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

  state.pendingWaste = entries;
  renderToday();

  if (!entries.length) {
    panel.classList.add('hidden');
    body.replaceChildren();
    return;
  }
  panel.classList.remove('hidden');

  // Solo el gerente ve los botones; al operario se le explica por que no los
  // tiene. Y nadie los ve sobre una merma que declaro el mismo: ofrecerlos
  // llevaria a un 409 que el servidor ya rechaza por segregacion de funciones.
  const isManager = state.session?.role === 'MANAGER';
  const me = state.session?.email;

  body.replaceChildren(...entries.map((entry) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-4 py-2.5 font-medium">${escapeHtml(entry.productName)}</td>
      <td class="px-4 py-2.5 font-mono text-xs">${escapeHtml(entry.batchCode)}</td>
      <td class="num px-4 py-2.5">${entry.quantity}</td>
      <td class="num px-4 py-2.5 text-risk-red">${money(entry.totalLoss)}</td>
      <td class="px-4 py-2.5">${WASTE_REASONS[entry.reason] ?? escapeHtml(entry.reason)}</td>
      <td class="px-4 py-2.5 text-ink-soft">${escapeHtml(entry.operatorId)}</td>
      <td class="px-4 py-2.5">${decisionCell(entry, isManager, me)}</td>`;
    return tr;
  }));
}

/** Quién puede decidir sobre esta merma, dicho en la propia tabla. */
function decisionCell(entry, isManager, me) {
  if (entry.operatorId === me) {
    return '<span class="text-sm text-ink-faint">La declaraste tú: debe resolverla otra persona</span>';
  }
  if (!isManager) {
    return '<span class="text-sm text-ink-faint">Requiere al gerente</span>';
  }
  return `
    <span class="flex gap-2">
      <button data-waste="${entry.wasteId}" data-decision="approve"
              class="rounded-md bg-brand px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-brand-deep">Aprobar</button>
      <button data-waste="${entry.wasteId}" data-decision="reject"
              class="rounded-md border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-soft transition-colors hover:border-ink-faint hover:text-ink">Devolver al stock</button>
    </span>`;
}

$('waste-rows').addEventListener('click', async (event) => {
  const button = event.target.closest('[data-waste]');
  if (!button) return;

  button.disabled = true;
  const decision = button.dataset.decision;
  try {
    const pending = await request(`/inventory/waste/${button.dataset.waste}/${decision}`, { method: 'POST' });
    renderPendingWaste(pending);
    await loadWasteHistory();
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

/* ───────────────────────── historial de bajas ───────────────────────── */

const WASTE_STATUS = {
  PENDING_APPROVAL: { label: 'Pendiente', tone: 'text-risk-amber' },
  APPROVED: { label: 'Aprobada', tone: 'text-risk-red' },
  REJECTED: { label: 'Devuelta al stock', tone: 'text-risk-green' }
};

/**
 * Pinta el historial.
 *
 * Una baja resuelta desaparece de la cola de arriba, que es lo correcto para
 * trabajar, pero deja sin respuesta la pregunta que viene después: cuánto se
 * perdió y quién lo autorizó.
 */
function renderWasteHistory(entries, status) {
  const body = $('waste-history-rows');
  const empty = $('waste-history-empty');
  const total = $('waste-history-total');

  if (!status) {
    state.wasteHistory = entries;
    renderToday();
  }

  if (!entries.length) {
    body.replaceChildren();
    empty.classList.remove('hidden');
    empty.textContent = status
      ? 'No hay bajas con ese estado.'
      : 'Todavía no se ha declarado ninguna baja.';
    total.textContent = '—';
    return;
  }
  empty.classList.add('hidden');

  // Solo suma lo aprobado: lo pendiente aún puede devolverse al stock y lo
  // rechazado nunca fue una pérdida.
  const perdida = entries
    .filter((entry) => entry.status === 'APPROVED')
    .reduce((sum, entry) => sum + Number(entry.totalLoss ?? 0), 0);

  total.textContent = `${plural(entries.length, 'baja', 'bajas')} · ${money(perdida)} consolidados`;

  body.replaceChildren(...entries.map((entry) => {
    const estado = WASTE_STATUS[entry.status] ?? { label: entry.status, tone: 'text-ink-soft' };
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="num px-4 py-2.5 text-xs text-ink-faint">${new Date(entry.loggedAt).toLocaleDateString('es-PE')}</td>
      <td class="px-4 py-2.5 font-medium">${escapeHtml(entry.productName)}</td>
      <td class="px-4 py-2.5 font-mono text-xs">${escapeHtml(entry.batchCode)}</td>
      <td class="num px-4 py-2.5 text-right">${entry.quantity}</td>
      <td class="num px-4 py-2.5 text-right ${entry.status === 'APPROVED' ? 'text-risk-red' : 'text-ink-faint'}">${money(entry.totalLoss)}</td>
      <td class="px-4 py-2.5">${WASTE_REASONS[entry.reason] ?? escapeHtml(entry.reason)}</td>
      <td class="px-4 py-2.5 text-xs text-ink-soft">${escapeHtml(entry.operatorId)}</td>
      <td class="px-4 py-2.5 text-xs">
        <span class="font-semibold ${estado.tone}">${estado.label}</span>
        ${entry.authorizedBy ? `<span class="block text-ink-faint">${escapeHtml(entry.authorizedBy)}</span>` : ''}
      </td>`;
    return tr;
  }));
}

async function loadWasteHistory() {
  const status = $('waste-history-filter').value;
  try {
    const query = status ? `?status=${status}&limit=100` : '?limit=100';
    renderWasteHistory(await request(`/inventory/waste${query}`), status);
  } catch {
    renderWasteHistory([], status);
  }
}

$('waste-history-filter').addEventListener('change', loadWasteHistory);

/* ───────────────────── panel 2: alertas y rebajas ───────────────────── */

/**
 * Pinta el panel de alertas.
 *
 * El contador de la pestaña se actualiza aqui y no en showTab porque es lo que
 * hace la alerta proactiva: el operario ve que hay tres decisiones pendientes
 * sin tener que entrar a mirar.
 */
function renderAlerts(board) {
  const body = $('alert-rows');
  const empty = $('alert-empty');
  const badge = $('alert-badge');

  state.alerts = board;
  renderToday();

  $('a-risk').textContent = money(board.valueAtRisk);
  $('a-margin').textContent = money(board.marginSacrificed);
  $('a-silenced').textContent = board.silenced;

  badge.textContent = board.alerts.length;
  badge.classList.toggle('hidden', board.alerts.length === 0);

  if (!board.alerts.length) {
    body.replaceChildren();
    empty.classList.remove('hidden');
    // Un panel vacio porque ya lo resolviste todo no dice lo mismo que un panel
    // vacio porque no hay nada que vencer.
    empty.textContent = board.silenced
      ? `Nada pendiente: ya decidiste sobre ${plural(board.silenced, 'lote', 'lotes')}.`
      : 'Nada vence en los próximos 10 días.';
    return;
  }

  empty.classList.add('hidden');
  body.replaceChildren(...board.alerts.map(alertRow));
}

function alertRow(alert) {
  const tr = document.createElement('tr');

  // Si el lote ya está en oferta se dice el precio, no el peldaño: es lo que
  // el operario puede ir a comprobar en la etiqueta del anaquel.
  const previo = alert.markdownPrice
    ? `<span class="text-xs font-semibold text-risk-amber">· en oferta a ${money(alert.markdownPrice)}</span>`
    : alert.previousDecision
      ? `<span class="text-xs text-ink-faint">· ya ${alert.previousDecision === 'APPLIED' ? 'rebajado' : 'descartado'}
           al ${alert.decidedStep}%</span>`
      : '';

  tr.innerHTML = `
    <td class="px-4 py-3 align-top">${stateChip(alert.daysToExpiry)}</td>
    <td class="px-4 py-3">
      <span class="font-semibold">${escapeHtml(alert.productName)}</span>
      <span class="font-mono text-xs text-ink-faint">${escapeHtml(alert.batchCode)}</span> ${previo}
      <span class="block text-xs text-ink-soft">${escapeHtml(alert.rationale)}</span>
    </td>
    <td class="num px-4 py-2.5 text-right">${alert.sellableQty}</td>
    <td class="num px-4 py-2.5 text-right text-ink-faint">${money(alert.retailPrice)}</td>
    <td class="num px-4 py-2.5 text-right font-bold">${
      alert.suggestedPrice === null
        ? '—'
        : `${money(alert.suggestedPrice)} <span class="text-xs font-normal text-risk-amber">−${alert.discountPercent}%</span>`
    }</td>
    <td class="num px-4 py-2.5 text-right text-xs text-ink-soft">
      cede ${money(alert.marginSacrificed)}<br>riesgo ${money(alert.valueAtRisk)}
    </td>
    <td class="px-4 py-2.5">${alertActions(alert)}</td>`;

  return tr;
}

/** Cada acción lleva a una tarea concreta de anaquel, no a un aviso más. */
function alertActions(alert) {
  // Con una oferta vigente, descartar deja de ser "no quiero" y pasa a ser
  // "quítala": es la única forma de volver al precio de lista desde aquí.
  const descartar = `
    <button data-alert="${alert.batchId}" data-decision="DISMISSED"
            class="rounded-md border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-soft transition-colors hover:border-ink-faint hover:text-ink">${
              alert.markdownPrice ? 'Quitar la oferta' : 'Descartar'
            }</button>`;

  // Antes de ceder margen o de perder el costo, mirar si se puede devolver.
  // Dos condiciones, y las dos importan:
  //   - que el lote viniera por una guia, porque sin proveedor conocido no hay
  //     a quien reclamarle;
  //   - que TODAVIA no haya vencido, que es la que casi se me escapa: el
  //     distribuidor canjea lo proximo a vencer, no lo vencido. Ofrecerlo
  //     sobre un lote muerto manda al operario a una pared.
  const canjear = alert.supplierName && alert.daysToExpiry >= 0
    ? `<button data-return="${alert.batchId}"
               title="Devolver a ${escapeHtml(alert.supplierName)} y recuperar el costo entero"
               class="rounded-md bg-risk-green px-3 py-1.5 text-xs font-semibold text-white
                      transition-opacity hover:opacity-90">Canjear</button>`
    : '';

  if (alert.action === 'RETIRAR') {
    // Vencido: el canje ya no cabe y la merma es la unica salida.
    return `
      <span class="flex flex-wrap gap-2">
        <button data-withdraw="${alert.batchId}"
                class="rounded bg-risk-red px-3 py-1 text-xs font-semibold text-white hover:opacity-90">Declarar merma</button>
        ${descartar}
      </span>`;
  }
  if (alert.action === 'REVISAR_PRECIO') {
    // El costo no deja sitio para rebajar. Si hay proveedor, devolverlo es
    // justo la salida que queda, y es mejor que cualquier precio.
    return `
      <span class="flex flex-wrap items-center gap-2 text-xs text-ink-faint">
        ${canjear}${canjear ? '' : 'Decide quien fija precios '}${descartar}
      </span>`;
  }
  return `
    <span class="flex flex-wrap gap-2">
      <button data-alert="${alert.batchId}" data-decision="APPLIED"
              class="rounded-md bg-brand px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-brand-deep">Rebajar a ${money(alert.suggestedPrice)}</button>
      ${canjear}
      ${descartar}
    </span>`;
}

$('alert-rows').addEventListener('click', async (event) => {
  const canje = event.target.closest('[data-return]');
  if (canje) {
    openReturnDialog(Number(canje.dataset.return));
    return;
  }

  const retirar = event.target.closest('[data-withdraw]');
  if (retirar) {
    jumpToWaste(retirar.dataset.withdraw);
    return;
  }

  const button = event.target.closest('[data-alert]');
  if (!button) return;

  button.disabled = true;
  try {
    const board = await request(`/inventory/alerts/${button.dataset.alert}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision: button.dataset.decision })
    });
    renderAlerts(board);
    await loadMarkdownHistory();
    toast(button.dataset.decision === 'APPLIED'
      ? 'Rebaja aplicada: el lote ya se vende a ese precio y queda firmada con tu nombre.'
      : 'Retirada. El lote vuelve al precio de lista y se avisará si baja un peldaño.', 'ok');
  } catch (error) {
    toast(error.message, 'error');
    button.disabled = false;
  }
});

/**
 * Lleva de la alerta a la baja de mercadería con el lote ya elegido.
 *
 * El selector de mermas se llena con la cola FEFO, que solo trae lotes activos,
 * de modo que un lote ya vencido no estaria en la lista: se añade aqui para que
 * el botón no prometa algo que la otra pestaña no puede hacer.
 */
function jumpToWaste(batchId) {
  const select = $('waste-batch');
  if (!Array.from(select.options).some((option) => option.value === String(batchId))) {
    const row = document.createElement('option');
    row.value = batchId;
    row.textContent = 'Lote vencido seleccionado desde las alertas';
    select.append(row);
  }
  select.value = String(batchId);
  $('waste-reason').value = 'EXPIRATION';

  showTab('inventario');
  $('tab-inventario').focus();
  select.scrollIntoView({ behavior: 'smooth', block: 'center' });
  toast('Lote vencido: declara la baja por vencimiento y el gerente la resolverá.', 'info');
}

async function loadAlerts() {
  try {
    renderAlerts(await request('/inventory/alerts'));
  } catch {
    renderAlerts({ valueAtRisk: 0, marginSacrificed: 0, silenced: 0, alerts: [] });
  }
}

function renderMarkdownHistory(rows) {
  const list = $('alert-history');
  state.markdowns = rows;
  renderToday();
  if (!rows.length) {
    list.innerHTML = '<li class="py-6 text-center text-ink-faint">Todavía no se ha decidido ninguna rebaja.</li>';
    return;
  }
  list.replaceChildren(...rows.map((row) => {
    const item = document.createElement('li');
    const precio = row.suggested_price === null ? 'sin rebaja' : `a ${money(row.suggested_price)}`;
    item.className = 'flex flex-wrap items-baseline gap-x-3 py-2';
    item.innerHTML = `
      <span class="num w-36 shrink-0 text-xs text-ink-faint">${new Date(row.decided_at).toLocaleString('es-PE')}</span>
      <span class="min-w-0 flex-1 truncate">
        <span class="font-semibold">${escapeHtml(row.product_name)}</span>
        <span class="font-mono text-xs text-ink-faint">${escapeHtml(row.batch_code)}</span>
      </span>
      <span class="text-sm">${row.decision === 'APPLIED' ? `Rebajado ${precio}` : 'Descartado'}
        <span class="text-xs text-ink-faint">(peldaño ${row.step_percent}%)</span>
      </span>
      <span class="text-xs text-ink-soft">${escapeHtml(row.decided_by)}</span>`;
    return item;
  }));
}

async function loadMarkdownHistory() {
  try {
    renderMarkdownHistory(await request('/inventory/alerts/history?limit=20'));
  } catch {
    renderMarkdownHistory([]);
  }
}

/* ─────────────────── panel 4: ingesta de Excel y cuarentena ─────────────────── */

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
    // La explicación de qué espera el archivo deja sitio al resultado: una vez
    // cargado, lo que importa es cuántas filas entraron.
    $('ingest-hint').classList.add('hidden');

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

/* ─────────────────────────── ventas por Excel ─────────────────────────── */

/**
 * Sube el mismo archivo para que se lea su hoja de Ventas.
 *
 * <p>Gemelo de uploadExcel, con dos diferencias que vienen de que aqui se
 * descuenta stock de verdad: el resumen cuenta unidades y facturacion -que es
 * lo que el operario cuadra contra la cinta de la caja- y la cuarentena no se
 * corrige reenviando el archivo, porque las lineas que ya entraron se
 * registrarian otra vez.
 */
async function uploadSales(file) {
  if (!state.token) {
    toast('Primero restaura la tienda demo para obtener acceso.', 'error');
    return;
  }
  const form = new FormData();
  form.append('file', file);

  toast(`Registrando las ventas de ${file.name}…`);
  try {
    const summary = await request('/inventory/ingest/ventas', { method: 'POST', body: form });
    pintarResumenDeVentas(summary);

    state.salesQuarantine = summary.quarantinedRows.map((row) => ({
      ...row, values: { ...row.originalValues }
    }));
    renderSalesQuarantine();

    // Una venta mueve el semaforo, las alertas y la rotacion: el tablero que se
    // estaba mirando ya no dice la verdad.
    await refreshDashboard();
    await loadUploadHistory();

    toast(summary.totalQuarantined
      ? `${plural(summary.totalRegistered, 'venta registrada', 'ventas registradas')} y `
        + `${plural(summary.totalQuarantined, 'linea sin registrar', 'lineas sin registrar')}.`
      : `${plural(summary.totalRegistered, 'venta registrada', 'ventas registradas')}: `
        + `${summary.totalUnits} unidades, ${money(summary.revenue)}.`,
      summary.totalQuarantined ? 'info' : 'ok');
  } catch (error) {
    toast(error.message, 'error');
  }
}

function pintarResumenDeVentas(summary) {
  $('v-read').textContent = summary.totalRowsProcessed;
  $('v-registered').textContent = summary.totalRegistered;
  $('v-units').textContent = summary.totalUnits;
  $('v-revenue').textContent = money(summary.revenue);
  if ($('v-avg-ticket')) {
    $('v-avg-ticket').textContent = summary.totalRegistered > 0
      ? money(Number(summary.revenue) / summary.totalRegistered)
      : '—';
  }
  if ($('v-avg-units')) {
    $('v-avg-units').textContent = summary.totalRegistered > 0
      ? `${(summary.totalUnits / summary.totalRegistered).toFixed(1)} u`
      : '—';
  }
  $('v-quarantined').textContent = summary.totalQuarantined;
  $('sales-summary').classList.remove('hidden');
  $('sales-summary').classList.add('grid');
}

/** Los tres campos corregibles de una venta, en el orden de la tabla. */
const SALES_FIELDS = ['sku', 'quantity', 'saleDate'];

function renderSalesQuarantine() {
  const panel = $('sales-quarantine');
  const body = $('sales-quarantine-rows');
  const filas = state.salesQuarantine ?? [];

  if (!filas.length) {
    panel.classList.add('hidden');
    body.replaceChildren();
    return;
  }
  panel.classList.remove('hidden');

  body.replaceChildren(...filas.map((row, index) => {
    const tr = document.createElement('tr');
    tr.className = 'align-top';
    const cells = SALES_FIELDS.map((field) => `
      <td class="px-4 py-2.5">
        <span contenteditable="true" data-sales-row="${index}" data-field="${field}"
              class="block min-w-[5rem] rounded px-1 ${campoCulpableDeVenta(row.errorCode, field)
                ? 'bg-risk-red/10 ring-1 ring-risk-red/40'
                : ''}">${escapeHtml(row.values[field] ?? '')}</span>
      </td>`).join('');

    tr.innerHTML = `
      <td class="num px-4 py-2.5 text-ink-faint">${row.rowNumber}</td>
      ${cells}
      <td class="px-4 py-2.5 text-risk-red">${escapeHtml(row.message)}</td>`;
    return tr;
  }));
}

/**
 * Resalta la celda que causo el rechazo.
 *
 * <p>SIN_STOCK señala la cantidad y no el SKU a proposito: el producto existe y
 * esta bien escrito, lo que no cuadra es cuantas unidades se pidieron.
 */
function campoCulpableDeVenta(errorCode, field) {
  const culpable = {
    SKU_VACIO: 'sku',
    SKU_LARGO: 'sku',
    SKU_DESCONOCIDO: 'sku',
    CANTIDAD_INVALIDA: 'quantity',
    CANTIDAD_NO_POSITIVA: 'quantity',
    CANTIDAD_EXCESIVA: 'quantity',
    SIN_STOCK: 'quantity',
    FECHA_INVALIDA: 'saleDate',
    FECHA_FUTURA: 'saleDate'
  }[errorCode];
  return culpable === field;
}

/** Reenvia solo las lineas corregidas, nunca el archivo. */
async function sendSalesFix() {
  const filas = state.salesQuarantine ?? [];
  if (!filas.length) return;

  // Se lee del DOM y no de un manejador por celda: las celdas son
  // contenteditable y el valor bueno es el que esta en pantalla al pulsar.
  document.querySelectorAll('[data-sales-row]').forEach((celda) => {
    const fila = filas[Number(celda.dataset.salesRow)];
    if (fila) fila.values[celda.dataset.field] = celda.textContent.trim();
  });

  const payload = {
    storeId: state.storeId,
    rows: filas.map((fila) => ({
      rowNumber: fila.rowNumber,
      sku: fila.values.sku,
      quantity: Number(fila.values.quantity),
      saleDate: fila.values.saleDate || null
    }))
  };

  const boton = $('btn-sales-fix');
  boton.disabled = true;
  try {
    const summary = await request('/inventory/ingest/ventas/correcciones', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    pintarResumenDeVentas(summary);

    state.salesQuarantine = summary.quarantinedRows.map((row) => ({
      ...row, values: { ...row.originalValues }
    }));
    renderSalesQuarantine();
    await refreshDashboard();

    toast(summary.totalQuarantined
      ? `${plural(summary.totalRegistered, 'venta registrada', 'ventas registradas')}. `
        + `Quedan ${plural(summary.totalQuarantined, 'linea', 'lineas')} por corregir.`
      : `${plural(summary.totalRegistered, 'venta registrada', 'ventas registradas')}. `
        + 'No queda ninguna linea pendiente.',
      summary.totalQuarantined ? 'info' : 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    boton.disabled = false;
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

  state.uploads = history;
  renderToday();

  const panel = $('upload-history');
  if (!history.length) {
    panel.classList.add('hidden');
    return;
  }
  panel.classList.remove('hidden');

  $('history-rows').replaceChildren(...history.map((entry) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-4 py-2.5 font-mono text-xs">
        ${escapeHtml(entry.fileName)}${etiquetaDeCarga(entry)}
      </td>
      <td class="px-4 py-2.5 text-ink-soft">${escapeHtml(entry.uploadedBy)}</td>
      <td class="num px-4 py-2.5 text-ink-soft">${fechaHora.format(new Date(entry.createdAt))}</td>
      <td class="num px-4 py-2.5">${entry.totalRows}</td>
      <td class="num px-4 py-2.5 text-risk-green">${entry.insertedRows}</td>
      <td class="num px-4 py-2.5 ${entry.quarantinedRows ? 'text-risk-red' : 'text-ink-faint'}">${entry.quarantinedRows}</td>
      <td class="px-4 py-2.5 font-mono text-xs text-ink-faint" title="SHA-256 del contenido">${entry.fileHash.slice(0, 12)}…</td>
      <td class="px-4 py-2.5">${revertAction(entry)}</td>`;
    return tr;
  }));
}

/**
 * Distingue una carga de ventas de una de inventario.
 *
 * <p>Hace falta porque son el MISMO archivo: la plantilla trae las dos hojas y
 * el operario la sube dos veces, de modo que sin esto el historial muestra dos
 * filas con el mismo nombre, la misma huella y casi la misma hora, y la columna
 * "Lotes" diria 88 en una y 14 en la otra sin que nada explique por que.
 */
function etiquetaDeCarga(entry) {
  if (entry.kind !== 'SALES') return '';
  return ` <span class="ml-1 rounded-full bg-brand-soft px-2 py-0.5 align-middle font-sans
                       text-[0.65rem] font-semibold text-brand-deep">ventas</span>`;
}

/** Revertir es decision del gerente; al operario se le muestra el estado, sin boton. */
function revertAction(entry) {
  if (entry.rolledBackAt) {
    return '<span class="text-sm text-ink-faint">Revertida</span>';
  }
  // Las ventas no se revierten: las unidades ya salieron. El servidor lo
  // rechaza igualmente, pero ofrecer el botón sería prometer algo que no
  // existe, y el gerente lo descubriría después de pulsarlo.
  if (entry.kind === 'SALES') {
    return '<span class="text-sm text-ink-faint" title="Las unidades ya salieron del inventario">'
      + 'No reversible</span>';
  }
  if (state.session?.role !== 'MANAGER') {
    return '<span class="text-sm text-ink-faint">—</span>';
  }
  return `<button data-upload="${entry.uploadId}"
                  class="rounded-md border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-soft transition-colors hover:border-ink-faint hover:text-ink">
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
/**
 * Descarga el inventario en el formato pedido.
 *
 * El Excel es para trabajar con los datos; el PDF, para firmar o entregar. Son
 * el mismo inventario contado igual, de modo que lo unico que cambia aqui es la
 * ruta y la extension.
 */
async function exportInventory(format) {
  const pdf = format === 'pdf';
  const button = $(pdf ? 'btn-export-pdf' : 'btn-export');
  button.disabled = true;
  try {
    const response = await fetch(`${api}/inventory/export${pdf ? '/pdf' : ''}`, { headers: headers() });
    if (!response.ok) {
      throw new Error('No se pudo generar el archivo.');
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `inventario-${new Date().toISOString().slice(0, 10)}.${pdf ? 'pdf' : 'xlsx'}`;
    link.click();
    URL.revokeObjectURL(url);
    toast(pdf ? 'Informe valorizado generado.' : 'Inventario exportado.', 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

/**
 * Hoja de etiquetas de los lotes que estan en oferta.
 *
 * <p>Va aparte de las otras dos exportaciones porque no es un informe: no lo
 * lee nadie, se recorta y se pega en el anaquel.
 */
async function downloadMarkdownLabels() {
  const button = $('btn-labels');
  button.disabled = true;
  try {
    const response = await fetch(`${api}/inventory/export/markdown-labels`, { headers: headers() });
    if (!response.ok) {
      throw new Error('No se pudieron generar las etiquetas.');
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `etiquetas-rebaja-${new Date().toISOString().slice(0, 10)}.pdf`;
    link.click();
    URL.revokeObjectURL(url);
    toast('Etiquetas generadas. El precio impreso es el que cobrará la caja.', 'ok');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    button.disabled = false;
  }
}

/* ─────────────────────── buscador global (Ctrl+K) ───────────────────────
 *
 * Con ocho secciones, llegar a un SKU obligaba a elegir seccion primero y
 * despues escribir. El indice se arma con lo que ya esta en memoria -catalogo,
 * lotes y las propias secciones-, de modo que el buscador no añade ni una
 * llamada al servidor.
 */

const FINDER_SECTIONS = [
  { tab: 'inicio', label: 'Lo primero de hoy', hint: 'Prioridades del día' },
  { tab: 'semaforo', label: 'Semáforo FEFO', hint: 'Cola de salida y venta rápida' },
  { tab: 'alertas', label: 'Qué rebajar hoy', hint: 'Precios sugeridos y etiquetas' },
  { tab: 'inventario', label: 'Inventario y lotes', hint: 'Buscador, exportación y mermas' },
  { tab: 'excel', label: 'Excel y versionado', hint: 'Cargas, cuarentena y reversión' },
  { tab: 'recepcion', label: 'Recepción con IA', hint: 'Guías de remisión por foto' },
  { tab: 'analisis', label: 'Análisis', hint: 'Tendencia, cobertura y proveedores' },
  { tab: 'diagnostico', label: 'Diagnóstico', hint: 'Quién va lento' }
];

let finderIndex = 0;

function finderMatches(query) {
  const term = query.trim().toLowerCase();

  const secciones = FINDER_SECTIONS
    .filter((section) => !term || section.label.toLowerCase().includes(term))
    .map((section) => ({ kind: 'Sección', title: section.label, detail: section.hint, tab: section.tab }));

  if (!term) {
    return secciones;
  }

  const productos = state.catalog
    .filter((product) => `${product.name} ${product.sku}`.toLowerCase().includes(term))
    .slice(0, 6)
    .map((product) => ({
      kind: 'Producto', title: product.name, detail: product.sku,
      tab: 'inventario', search: product.sku
    }));

  const lotes = state.batches
    .filter((batch) => `${batch.batchCode} ${batch.productName}`.toLowerCase().includes(term))
    .slice(0, 6)
    .map((batch) => ({
      kind: 'Lote', title: batch.batchCode,
      detail: `${batch.productName} · ${batch.currentQty} u · vence en ${
        plural(batch.daysToExpiry, 'día', 'días')}`,
      tab: 'inventario', search: batch.batchCode
    }));

  return [...productos, ...lotes, ...secciones].slice(0, 12);
}

function renderFinder() {
  const list = $('finder-results');
  const resultados = finderMatches($('finder-input').value);
  finderIndex = Math.min(finderIndex, Math.max(resultados.length - 1, 0));

  if (!resultados.length) {
    list.innerHTML = '<li class="px-4 py-8 text-center text-sm text-ink-faint">Nada coincide.</li>';
    return;
  }

  list.replaceChildren(...resultados.map((item, i) => {
    const li = document.createElement('li');
    li.className = `flex cursor-pointer items-baseline gap-3 px-4 py-2.5 ${
      i === finderIndex ? 'bg-brand-tint' : ''}`;
    li.dataset.tab = item.tab;
    if (item.search) li.dataset.search = item.search;
    li.innerHTML = `
      <span class="w-16 shrink-0 text-xs text-ink-faint">${item.kind}</span>
      <span class="min-w-0 flex-1">
        <span class="block truncate font-medium">${escapeHtml(item.title)}</span>
        <span class="block truncate text-xs text-ink-soft">${escapeHtml(item.detail)}</span>
      </span>`;
    return li;
  }));
}

function openFinder() {
  finderIndex = 0;
  $('finder-input').value = '';
  renderFinder();
  $('finder-dialog').showModal();
  $('finder-input').focus();
}

/** Abrir un resultado deja la busqueda hecha, no solo la seccion abierta. */
function runFinder(target) {
  if (!target) return;
  $('finder-dialog').close();
  showTab(target.dataset.tab);

  if (target.dataset.search) {
    $('batch-search').value = target.dataset.search;
    loadBatches();
  }
}

$('finder-input').addEventListener('input', () => {
  finderIndex = 0;
  renderFinder();
});

$('finder-dialog').addEventListener('keydown', (event) => {
  const items = [...$('finder-results').children].filter((node) => node.dataset.tab);
  if (!items.length) return;

  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    finderIndex = (finderIndex + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    renderFinder();
  } else if (event.key === 'Enter') {
    event.preventDefault();
    runFinder(items[finderIndex]);
  }
});

$('finder-results').addEventListener('click', (event) => runFinder(event.target.closest('[data-tab]')));

document.addEventListener('keydown', (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    openFinder();
  }
});

/** Campos editables de la cuarentena, en el orden de la tabla. */
const QUARANTINE_FIELDS = [
  'sku', 'name', 'category', 'unit_cost', 'retail_price', 'batch_code', 'expiration_date', 'initial_qty'
];

function renderQuarantine() {
  const panel = $('quarantine');
  const body = $('quarantine-rows');

  renderToday();

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
      <td class="px-4 py-2.5">
        <span contenteditable="true" data-row="${index}" data-field="${field}"
              class="block min-w-[5rem] rounded px-1 ${fieldIsCulprit(row.errorCode, field)
                ? 'bg-risk-red/10 ring-1 ring-risk-red/40'
                : ''}">${escapeHtml(row.values[field] ?? '')}</span>
      </td>`).join('');

    tr.innerHTML = `
      <td class="num px-4 py-2.5 text-ink-faint">${row.rowNumber}</td>
      ${cells}
      <td class="px-4 py-2.5 text-risk-red">${escapeHtml(row.message)}</td>`;
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

/* ─────────────── panel 5: recepcion documental con vision IA ─────────────── */

/**
 * Saldo de escaneos del dia.
 *
 * El escaneo es lo unico que cuesta dinero fuera de nuestra infraestructura, y
 * la cuota es una sola para todo el despliegue; decirlo por adelantado evita
 * que el operario lo descubra con la guia ya en la mano.
 */
function renderQuota(quota) {
  if (!quota) return;
  state.quota = quota;
  renderToday();
  const left = $('quota-left');
  left.textContent = quota.remainingToday;
  left.className = quota.remainingToday === 0
    ? 'num font-semibold text-risk-red'
    : quota.remainingToday <= 3 ? 'num font-semibold text-risk-amber' : 'num font-semibold text-ink';
}

async function loadQuota() {
  try {
    renderQuota(await request('/receipts/quota'));
  } catch {
    $('quota-left').textContent = '—';
  }
}

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
    renderQuota(scan.quota);
    toast(scan.unmatchedLines
      ? `Guía leída. ${plural(scan.unmatchedLines, 'línea necesita', 'líneas necesitan')} que elijas el producto.`
      : 'Guía leída y todas las líneas mapeadas.',
      scan.unmatchedLines ? 'info' : 'ok');
  } catch (error) {
    toast(error.message, 'error');
    // Si el rechazo fue por cupo, el saldo que se muestra ya no es el bueno.
    await loadQuota();
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
      <td class="px-4 py-2.5">
        <span class="font-semibold">${escapeHtml(line.descriptionRaw)}</span>
      </td>
      <td class="px-4 py-2.5">
        <select data-line="${index}" data-field="productId"
                class="w-56 rounded-md border px-2 py-1 ${matched ? 'border-line' : 'border-risk-red bg-risk-red/5'}">
          <option value="">Sin asignar</option>
          ${options}
        </select>
      </td>
      <td class="num px-4 py-2.5 ${scoreTone(score)}">${score == null ? 'sin candidato' : score + '%'}</td>
      <td class="num px-4 py-2.5">${line.invoicedQty ?? '—'}</td>
      <td class="px-4 py-2.5">
        <input type="number" min="0" step="1" data-line="${index}" data-field="receivedQty"
               value="${line.invoicedQty ?? 0}"
               class="num w-24 rounded-md border border-line px-2 py-1 text-right">
      </td>
      <td class="px-4 py-2.5">
        <input type="text" data-line="${index}" data-field="batchCode" value="${escapeHtml(line.batchCode ?? '')}"
               placeholder="SIN-LOTE" class="w-32 rounded-md border border-line px-2 py-1 font-mono text-xs">
      </td>
      <td class="px-4 py-2.5">
        <input type="date" data-line="${index}" data-field="expirationDate" value="${line.expirationDate ?? ''}"
               class="num rounded-md border border-line px-2 py-1">
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

/* ────────────────────── canje al proveedor ──────────────────────
 *
 * El distribuidor grande -lacteos, embutidos, panaderia industrial- canjea la
 * mercaderia proxima a vencer si se le avisa a tiempo. Es la unica salida que
 * recupera el costo entero, asi que se ofrece ANTES que la merma, y solo
 * cuando se sabe quien trajo el lote.
 */

const RETURN_STATUS = {
  PENDING: { label: 'Esperando al proveedor', chip: 'chip-amber' },
  CREDITED: { label: 'Abonado', chip: 'chip-green' },
  REFUSED: { label: 'No lo aceptó', chip: 'chip-red' }
};

/**
 * Abre el dialogo del canje con el lote ya puesto.
 *
 * <p>El lote entero se busca en la alerta en vez de colgarlo del boton en
 * atributos: el precio y el proveedor ya estan en memoria, y repetirlos en el
 * DOM seria una segunda copia que puede quedarse vieja.
 */
function openReturnDialog(batchId) {
  const alerta = state.alerts?.alerts?.find((a) => a.batchId === batchId);
  if (!alerta) return;

  state.returnDraft = alerta;

  $('return-product').textContent = alerta.productName;
  $('return-batch').textContent = alerta.batchCode;
  $('return-supplier').textContent = alerta.supplierName;
  $('return-max').textContent = `(hay ${alerta.sellableQty})`;
  $('return-qty').max = alerta.sellableQty;
  $('return-qty').value = alerta.sellableQty;
  $('return-error').classList.add('hidden');

  updateRecovered();
  $('return-dialog').showModal();
  $('return-qty').focus();
  $('return-qty').select();
}

/** Lo que vuelve a caja, recalculado mientras se teclea. */
function updateRecovered() {
  const alerta = state.returnDraft;
  if (!alerta) return;

  const quantity = Number($('return-qty').value);
  const valido = Number.isInteger(quantity) && quantity >= 1 && quantity <= alerta.sellableQty;

  $('return-recovers').textContent = valido
    ? money(quantity * Number(alerta.unitCost))
    : '—';
  $('return-submit').disabled = !valido;
}

async function declareReturn(event) {
  event.preventDefault();
  const alerta = state.returnDraft;
  const quantity = Number($('return-qty').value);
  const button = $('return-submit');
  const error = $('return-error');

  error.classList.add('hidden');
  button.disabled = true;

  try {
    await request('/inventory/returns', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ batchId: alerta.batchId, quantity })
    });
    $('return-dialog').close();

    await Promise.all([loadReturns(), loadAlerts(), loadBatches()]);
    await refreshDashboard();
    toast(`${plural(quantity, 'unidad apartada', 'unidades apartadas')} para el canje. `
        + 'Ya no se venden; confirma cuando el proveedor abone.', 'ok');
  } catch (failure) {
    // El error se queda dentro del dialogo: el operario tiene ahi la cantidad
    // que acaba de escribir y puede corregirla sin volver a empezar.
    error.textContent = failure.message;
    error.classList.remove('hidden');
    button.disabled = false;
  }
}

$('return-qty').addEventListener('input', updateRecovered);
$('return-cancel').addEventListener('click', () => $('return-dialog').close());
$('return-form').addEventListener('submit', declareReturn);

function renderReturns(entries) {
  const body = $('return-rows');
  const empty = $('return-empty');
  const total = $('return-total');

  state.returns = entries;

  if (!entries.length) {
    body.replaceChildren();
    empty.classList.remove('hidden');
    empty.textContent = 'Todavía no has devuelto nada a ningún proveedor.';
    total.textContent = '—';
    return;
  }
  empty.classList.add('hidden');

  // Solo suma lo abonado: lo pendiente todavia puede rechazarse, y lo
  // rechazado no recupero nada.
  const recuperado = entries
    .filter((entry) => entry.status === 'CREDITED')
    .reduce((sum, entry) => sum + Number(entry.recovered ?? 0), 0);

  total.textContent = `${plural(entries.length, 'canje', 'canjes')} · ${
    money(recuperado)} ya abonados`;

  const esGerente = state.session?.role === 'MANAGER';

  body.replaceChildren(...entries.map((entry) => {
    const estado = RETURN_STATUS[entry.status] ?? { label: entry.status, chip: 'chip-neutral' };
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-4 py-2.5 font-medium">${escapeHtml(entry.productName)}</td>
      <td class="px-4 py-2.5 font-mono text-xs text-ink-soft">${escapeHtml(entry.batchCode)}</td>
      <td class="px-4 py-2.5">${escapeHtml(entry.supplierName)}</td>
      <td class="num px-4 py-2.5 text-right">${entry.quantity}</td>
      <td class="num px-4 py-2.5 text-right ${
        entry.status === 'CREDITED' ? 'font-semibold text-risk-green' : 'text-ink-faint'}">${
        money(entry.recovered)}</td>
      <td class="px-4 py-2.5">
        <span class="chip ${estado.chip}">${estado.label}</span>
        ${entry.resolvedBy ? `<span class="block text-xs text-ink-faint">${escapeHtml(entry.resolvedBy)}</span>` : ''}
      </td>
      <td class="px-4 py-2.5">${
        entry.status !== 'PENDING' ? ''
          : esGerente ? `
            <span class="flex gap-2">
              <button data-credit="${entry.returnId}"
                      class="rounded-md bg-risk-green px-3 py-1.5 text-xs font-semibold text-white hover:opacity-90">Abonó</button>
              <button data-refuse="${entry.returnId}"
                      class="rounded-md border border-line px-3 py-1.5 text-xs font-semibold text-ink-soft hover:border-ink-faint">No lo aceptó</button>
            </span>`
          : '<span class="text-xs text-ink-faint">Lo confirma el gerente</span>'}</td>`;
    return tr;
  }));
}

async function loadReturns() {
  try {
    renderReturns(await request('/inventory/returns?limit=100'));
  } catch {
    renderReturns([]);
  }
}

$('return-rows').addEventListener('click', async (event) => {
  const abono = event.target.closest('[data-credit]');
  const rechazo = event.target.closest('[data-refuse]');
  const boton = abono ?? rechazo;
  if (!boton) return;

  boton.disabled = true;
  const id = abono ? abono.dataset.credit : rechazo.dataset.refuse;

  try {
    await request(`/inventory/returns/${id}/${abono ? 'credit' : 'refuse'}`, { method: 'POST' });
    await Promise.all([loadReturns(), loadBatches(), loadAlerts()]);
    await refreshDashboard();
    toast(abono
      ? 'Canje abonado: el costo se recuperó, no fue merma.'
      : 'La mercadería vuelve al stock. Si ya venció, tendrás que darla de baja.', 'ok');
  } catch (error) {
    boton.disabled = false;
    toast(error.message, 'error');
  }
});

/* ─────────────────── panel 7: por que pasa lo que pasa ───────────────────
 *
 * Las tres preguntas que el resto de la aplicacion no responde: si la cosa va a
 * mejor, por que se compro de mas, y de quien viene lo que acaba en riesgo.
 */

const diaCorto = new Intl.DateTimeFormat('es-PE', { day: '2-digit', month: 'short' });

/** Convierte "2026-10-20" en una fecha local sin que la zona la corra un dia. */
function parseDay(value) {
  const [year, month, day] = String(value).slice(0, 10).split('-').map(Number);
  return new Date(year, month - 1, day);
}

/** "20-oct." abrevia con punto; dentro de una frase eso deja dos seguidos. */
function diaEnFrase(value) {
  return diaCorto.format(parseDay(value)).replace(/\.$/, '');
}

async function loadRiskHistory() {
  try {
    state.riskSeries = await request('/inventory/risk-history?days=30');
  } catch {
    state.riskSeries = [];
  }
  renderTrend();
  renderToday();
}

/**
 * La serie del valor en riesgo.
 *
 * <p>Con menos de dos puntos no se dibuja nada. Una linea plana de un solo dia
 * parece un dato y no lo es: la tabla empieza a llenarse el dia del despliegue
 * y hasta mañana no hay nada que comparar. Decirlo cuesta una frase.
 */
function renderTrend() {
  const chart = $('trend-chart');
  const note = $('trend-note');
  const delta = $('trend-delta');
  const serie = state.riskSeries;

  delta.textContent = '';

  if (serie.length < 2) {
    chart.replaceChildren();
    note.textContent = serie.length
      ? 'Hay una sola foto, la de hoy. Mañana ya habrá con qué compararla.'
      : 'Todavía no hay fotos. Se toma una al cierre de cada día.';
    return;
  }

  const valores = serie.map((punto) => Number(punto.value_at_risk));
  const techo = Math.max(...valores, 1);
  const ancho = 100;
  const alto = 32;
  const paso = ancho / (valores.length - 1);
  const puntos = valores.map((valor, i) => `${(i * paso).toFixed(2)},${
    (alto - (valor / techo) * (alto - 2) - 1).toFixed(2)}`);

  note.textContent = `${plural(serie.length, 'día medido', 'días medidos')}, del ${
    diaEnFrase(serie[0].snapshot_date)} al ${
    diaEnFrase(serie.at(-1).snapshot_date)}. Máximo ${money(techo)}.`;

  const cambio = valores.at(-1) - valores.at(-2);
  if (cambio !== 0) {
    // Que el riesgo baje es la buena noticia, al reves que en casi cualquier
    // otra grafica: por eso el verde va con el signo negativo.
    delta.className = `num text-right text-sm font-semibold ${
      cambio < 0 ? 'text-risk-green' : 'text-risk-red'}`;
    delta.textContent = `${cambio < 0 ? '−' : '+'}${money(Math.abs(cambio))} desde el día anterior`;
  }

  chart.innerHTML = `
    <svg viewBox="0 0 ${ancho} ${alto}" preserveAspectRatio="none" class="h-28 w-full"
         role="img" aria-label="Valor en riesgo de los últimos ${serie.length} días">
      <polygon points="0,${alto} ${puntos.join(' ')} ${ancho},${alto}" fill="#DBE6FA"></polygon>
      <polyline points="${puntos.join(' ')}" fill="none" stroke="#3F6FD0" stroke-width="1.5"
                vector-effect="non-scaling-stroke" stroke-linejoin="round"></polyline>
    </svg>
    <p class="num mt-2 flex justify-between text-xs text-ink-faint">
      <span>${money(valores[0])}</span>
      <span class="font-semibold text-ink">${money(valores.at(-1))} hoy</span>
    </p>`;
}

/** La misma serie, resumida en una linea, junto a la cifra de la pantalla de inicio. */
function renderTodayTrend() {
  const line = $('t-risk-trend');
  const serie = state.riskSeries;

  if (serie.length < 2) {
    line.classList.add('hidden');
    return;
  }
  const cambio = Number(serie.at(-1).value_at_risk) - Number(serie.at(-2).value_at_risk);
  line.classList.remove('hidden');
  if (cambio === 0) {
    line.className = 'mt-1.5 text-sm font-semibold text-ink-soft';
    line.textContent = 'Igual que el día anterior.';
    return;
  }
  line.className = `mt-1.5 text-sm font-semibold ${cambio < 0 ? 'text-risk-green' : 'text-risk-red'}`;
  line.textContent = `${cambio < 0 ? 'Baja' : 'Sube'} ${money(Math.abs(cambio))} desde el día anterior.`;
}

async function loadInsights() {
  try {
    const insights = await request('/inventory/insights');
    $('coverage-window').textContent = insights.salesWindowDays;
    $('category-window').textContent = insights.salesWindowDays;
    state.categories = insights.categories;
    // Al llegar se muestran todas; quitar es del usuario, no del estado inicial.
    state.categoriesOff = new Set();
    renderCategories();
    renderCoverage(insights.coverage, insights.salesWindowDays);
    renderSuppliers(insights.suppliers);
  } catch (error) {
    toast(error.message, 'error');
  }
}

/**
 * El inventario por familia, con la plata que no va a salir a tiempo.
 *
 * <p>Quitar una familia no la esconde: la saca del calculo. La escala y los
 * totales se rehacen con lo que queda, que es lo unico que hace util el
 * filtro —comparar la mercaderia fresca entre si sin que la aplaste el valor
 * de los abarrotes, que no se vencen nunca.
 */
function renderCategories() {
  const filtros = $('category-filters');
  const todas = state.categories ?? [];

  if (!todas.length) {
    filtros.replaceChildren();
    $('category-total').textContent = '';
    $('category-chart').innerHTML = `<p class="py-6 text-center text-sm text-ink-faint">
      No hay existencias que agrupar todavía.</p>`;
    return;
  }

  filtros.replaceChildren(...todas.map((fila) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.dataset.categoria = String(fila.categoria);
    chip.textContent = enFrase(fila.categoria);
    vestirChip(chip, !state.categoriesOff.has(String(fila.categoria)));
    return chip;
  }));

  renderCategoryBars();
}

/**
 * El aspecto de un chip segun este dentro o fuera.
 *
 * <p>Se cambia sobre el boton que ya existe en lugar de volver a crearlo. Con
 * el raton da igual, pero quien lo active con el teclado perderia el foco en
 * el acto: el nodo que tenia enfocado deja de estar en el documento y el foco
 * se cae al body, de modo que el siguiente Tab empieza desde el principio de
 * la pagina. Un filtro no se puede recorrer asi.
 */
function vestirChip(chip, activa) {
  chip.setAttribute('aria-pressed', String(activa));
  chip.className = activa
    ? 'rounded-full border border-brand bg-brand-soft px-3 py-1 text-xs font-semibold text-brand-deep transition-colors'
    : 'rounded-full border border-line px-3 py-1 text-xs font-semibold text-ink-faint line-through transition-colors hover:border-line-strong';
}

/** Solo las barras: los chips los mantiene el que los pulsa. */
function renderCategoryBars() {
  const chart = $('category-chart');
  const total = $('category-total');
  const todas = state.categories ?? [];

  const activas = todas.filter((fila) => !state.categoriesOff.has(String(fila.categoria)));

  if (!activas.length) {
    total.textContent = '';
    chart.innerHTML = `<p class="py-6 text-center text-sm text-ink-faint">
      Quitaste todas las familias. Vuelve a añadir alguna para ver el reparto.</p>`;
    return;
  }

  // La escala se rehace con lo que queda visible: si no, quitar la familia mas
  // grande dejaria el resto aplastado contra el margen izquierdo y el filtro
  // no serviria de nada.
  const techo = Math.max(...activas.map((fila) => Number(fila.valor)), 1);
  const sinSalida = activas.reduce((suma, fila) => suma + Number(fila.sin_salida), 0);
  const valor = activas.reduce((suma, fila) => suma + Number(fila.valor), 0);

  total.textContent = sinSalida > 0
    ? `${money(sinSalida)} sin salida de ${money(valor)}`
    : `${money(valor)}, todo con tiempo de venderse`;
  total.className = sinSalida > 0
    ? 'num text-right text-sm font-semibold text-risk-red'
    : 'num text-right text-sm font-semibold text-risk-green';

  chart.replaceChildren(...activas.map((fila) => barraDeCategoria(fila, techo)));
}

/** Una barra: el ancho es el dinero, el rojo es el que no alcanza a venderse. */
function barraDeCategoria(fila, techo) {
  const valor = Number(fila.valor);
  const perdido = Number(fila.sin_salida);
  const cobertura = fila.cobertura_media === null ? null : Number(fila.cobertura_media);
  const sinSalida = Number(fila.productos_sin_salida);

  const ancho = Math.max((valor / techo) * 100, 1.5);
  // El rojo se mide sobre la barra, no sobre el total: es la fraccion de ESTA
  // familia que se pierde, que es lo que se compara de una fila a otra.
  const rojo = valor > 0 ? (perdido / valor) * 100 : 0;

  const fragmento = document.createElement('div');
  fragmento.className = 'mb-3.5 last:mb-0';
  fragmento.innerHTML = `
    <div class="flex items-baseline justify-between gap-3 text-sm">
      <span class="font-medium">${escapeHtml(enFrase(fila.categoria))}</span>
      <span class="num text-xs ${sinSalida > 0 ? 'font-semibold text-risk-red' : 'text-ink-faint'}">${
        sinSalida > 0
          ? `${money(perdido)} · ${sinSalida} de ${fila.productos} no da tiempo`
          : `${money(valor)} · sale a tiempo`}</span>
    </div>
    <div class="mt-1.5 flex items-center gap-2.5">
      <div class="h-3.5 flex-1 overflow-hidden rounded-r bg-canvas">
        <div class="flex h-full" style="width:${ancho.toFixed(2)}%">
          <div class="h-full bg-risk-red" style="width:${rojo.toFixed(2)}%"></div>
          <div class="h-full flex-1 bg-brand"></div>
        </div>
      </div>
      <span class="num w-16 shrink-0 text-right text-xs text-ink-faint"
            title="Días de venta que hay en el anaquel">${
        cobertura === null ? '—' : `${cobertura} d`}</span>
    </div>`;
  return fragmento;
}

/** MAYUSCULAS DE BASE DE DATOS a texto que se pueda leer en una pantalla. */
function enFrase(texto) {
  const limpio = String(texto ?? '').toLowerCase();
  return limpio.charAt(0).toUpperCase() + limpio.slice(1);
}

/**
 * Dias de venta que hay en el anaquel.
 *
 * El veredicto es la columna que paga la tabla: comparar cobertura con dias
 * restantes es lo que convierte dos numeros en una decision de compra.
 */
function renderCoverage(rows, ventana) {
  const body = $('coverage-rows');

  if (!rows.length) {
    body.innerHTML = `<tr><td colspan="7" class="px-4 py-10 text-center text-sm text-ink-faint">
      No hay productos con existencias.</td></tr>`;
    return;
  }

  body.replaceChildren(...rows.map((row) => {
    const cobertura = row.dias_cobertura === null ? null : Number(row.dias_cobertura);
    const restantes = Number(row.dias_hasta_vencer);
    const tr = document.createElement('tr');

    let veredicto = `<span class="chip chip-neutral">Sin ventas aún</span>`;
    if (cobertura !== null && restantes >= 0) {
      veredicto = cobertura > restantes
        ? `<span class="chip chip-red">No da tiempo</span>`
        : cobertura > restantes * 0.7
          ? `<span class="chip chip-amber">Justo</span>`
          : `<span class="chip chip-green">Sale a tiempo</span>`;
    } else if (restantes < 0) {
      veredicto = `<span class="chip chip-expired">Vencido</span>`;
    }

    tr.innerHTML = `
      <td class="px-4 py-2.5 font-medium">${escapeHtml(row.name)}</td>
      <td class="px-4 py-2.5 font-mono text-xs text-ink-soft">${escapeHtml(row.sku)}</td>
      <td class="num px-4 py-2.5 text-right">${row.unidades}</td>
      <td class="num px-4 py-2.5 text-right text-ink-soft" title="En los últimos ${ventana} días">${
        Number(row.vendidas_ventana)}</td>
      <td class="num px-4 py-2.5 text-right font-semibold">${
        cobertura === null ? '—' : `${cobertura} d`}</td>
      <td class="num px-4 py-2.5 text-right ${restantes <= 5 ? 'text-risk-red font-semibold' : 'text-ink-soft'}">${
        restantes} d</td>
      <td class="px-4 py-2.5">${veredicto}</td>`;
    return tr;
  }));
}

function renderSuppliers(rows) {
  const body = $('supplier-rows');

  if (!rows.length) {
    body.innerHTML = `<tr><td colspan="6" class="px-4 py-10 text-center text-sm text-ink-faint">
      No hay lotes que agrupar todavía.</td></tr>`;
    return;
  }

  body.replaceChildren(...rows.map((row) => {
    const enRiesgo = Number(row.en_riesgo);
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="px-4 py-2.5">
        <span class="font-medium">${escapeHtml(row.proveedor)}</span>
        ${row.con_guia ? '' : '<span class="block text-xs text-ink-faint">Sin trazabilidad de proveedor</span>'}
      </td>
      <td class="num px-4 py-2.5 text-right">${row.lotes}</td>
      <td class="num px-4 py-2.5 text-right">${money(row.valor)}</td>
      <td class="num px-4 py-2.5 text-right ${enRiesgo > 0 ? 'font-semibold text-risk-red' : 'text-ink-faint'}">${
        money(enRiesgo)}</td>
      <td class="num px-4 py-2.5 text-right ${Number(row.vencido) > 0 ? 'text-risk-red' : 'text-ink-faint'}">${
        money(row.vencido)}</td>
      <td class="num px-4 py-2.5 text-right ${Number(row.merma) > 0 ? 'text-risk-red' : 'text-ink-faint'}">${
        money(row.merma)}</td>`;
    return tr;
  }));
}

/* ───────────── panel 6: a quien mirar cuando algo va lento ───────────── */

/**
 * Pinta el diagnostico.
 *
 * El veredicto va arriba y en palabras porque es lo unico que se mira durante
 * una incidencia; los numeros quedan debajo para comprobarlo con calma.
 */
function renderDiagnostics(report) {
  // El veredicto se tiñe entero según haya culpable o no: el color del aviso es
  // el propio aviso, no un filete a un lado que hay que saber interpretar.
  const hayCulpable = /^(El pool|Gemini|Supabase)/.test(report.verdict);
  $('diag-verdict').textContent = report.verdict;
  $('diag-verdict').className = hayCulpable
    ? 'mt-6 rounded-lg border border-risk-red/30 bg-risk-red-tint px-4 py-3 text-sm font-semibold text-risk-red'
    : 'mt-6 rounded-lg border border-risk-green/30 bg-risk-green-tint px-4 py-3 text-sm font-semibold text-risk-green';

  const cards = report.suspects.map((suspect) => card(
    suspect.name,
    suspect.p95Millis === null ? '—' : `${suspect.p95Millis} ms`,
    `p95 · p50 ${suspect.p50Millis ?? '—'} ms · máx ${suspect.maxMillis} ms`,
    `${plural(suspect.calls, 'llamada', 'llamadas')} · ${suspect.detail}`));

  // El pool es el tercero porque se confunde con la base: una espera por
  // conexion se ve desde fuera igual que Supabase yendo lento.
  cards.push(card(
    'Pool de conexiones',
    `${report.pool.acquireTypicalMillis} ms`,
    `espera típica · ${report.pool.active}/${report.pool.max} en uso · ${report.pool.pendingThreads} esperando`,
    // El máximo casi siempre es el arranque: abrir la primera conexión contra
    // Supabase cuesta TCP y TLS. Decirlo evita una alarma sin motivo.
    `Máxima ${report.pool.acquireMaxMillis} ms, normalmente al abrir la primera conexión.`));

  $('diag-cards').replaceChildren(...cards);
  // Un salto en los bloqueos no es lentitud: es alguien probando contraseñas.
  const bloqueos = report.blockedLogins
    ? ` ${plural(report.blockedLogins, 'cuenta o IP frenada', 'cuentas o IP frenadas')} por intentos fallidos.`
    : '';

  $('diag-heap').textContent =
    `Memoria del contenedor: ${report.heapUsedMb} MB en uso de ${report.heapMaxMb} MB. `
    + 'Un “resto” alto en los tiempos con la memoria al límite suele ser el recolector, no un proveedor.'
    + bloqueos;
}

function card(title, big, small, detail) {
  const node = document.createElement('div');
  node.className = 'border border-line bg-white p-4';
  node.innerHTML = `
    <p class="text-sm text-ink-faint">${escapeHtml(title)}</p>
    <p class="num mt-1 text-2xl font-bold">${escapeHtml(big)}</p>
    <p class="num mt-1 text-xs text-ink-soft">${escapeHtml(small)}</p>
    <p class="mt-2 text-xs text-ink-faint">${escapeHtml(detail)}</p>`;
  return node;
}

async function loadDiagnostics() {
  try {
    renderDiagnostics(await request('/ops/diagnostics'));
  } catch (error) {
    // El operario no tiene por que ver la infraestructura; se dice, no se calla.
    $('diag-verdict').className = 'mt-6 rounded-lg border border-line bg-surface px-4 py-3 text-sm shadow-card';
    $('diag-verdict').textContent = state.session?.role === 'MANAGER'
      ? `No se pudo medir: ${error.message}`
      : 'El diagnóstico de rendimiento lo ve el gerente de tienda.';
    $('diag-cards').replaceChildren();
    $('diag-heap').textContent = '';
  }
}

/* ──────────────────────────── arranque ──────────────────────────── */

$('btn-diag').addEventListener('click', loadDiagnostics);

$('btn-demo').addEventListener('click', resetDemo);

// En movil la barra lateral esta colapsada, asi que las dos acciones de entorno
// se repiten arriba. Reusan el mismo manejador: un segundo camino al mismo sitio,
// no una segunda implementacion.
$('btn-demo-mobile').addEventListener('click', resetDemo);
$('btn-demo-home').addEventListener('click', resetDemo);
$('btn-switch-mobile').addEventListener('click', () => $('btn-switch').click());
$('sale-form').addEventListener('submit', registerSale);
$('waste-form').addEventListener('submit', reportWaste);
$('btn-export').addEventListener('click', () => exportInventory('xlsx'));
$('btn-export-pdf').addEventListener('click', () => exportInventory('pdf'));
$('btn-labels').addEventListener('click', downloadMarkdownLabels);
$('btn-bulkfix').addEventListener('click', sendBulkFix);
$('btn-sales-fix').addEventListener('click', sendSalesFix);
$('btn-confirm').addEventListener('click', confirmReceipt);

wireDropzone('dropzone', 'file-excel', uploadExcel);
wireDropzone('scan-dropzone', 'file-scan', uploadReceipt);
wireDropzone('sales-dropzone', 'file-sales', uploadSales);

renderToday();
pingBackend();
window.setInterval(pingBackend, 60_000);

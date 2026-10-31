// S1: GET /api/doctor/{id}/available-slots?date=YYYY-MM-DD con un usuario autenticado.
// Ejecutar con loadtests/run.sh (fija la imagen de k6, la red y el directorio de resultados).
// Ver loadtests/README.md.

import http from 'k6/http';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Gauge, Rate } from 'k6/metrics';

// Topes duros: un perfil mal configurado no puede pasar de aquí
const MAX_RATE = 50;
const MAX_DURATION_S = 600;
const MAX_VUS = 100;

const PROFILE = env('PROFILE', 'smoke');
const TARGET = env('TARGET_URL', '').replace(/\/+$/, '');
const ORIGIN = env('SCHEDULING_ORIGIN', 'MANUAL');
const MAX_PAIRS = intEnv('MAX_PAIRS', 10, 1, 50);
const DATES_PER_DOCTOR = intEnv('DATES_PER_DOCTOR', 3, 1, 20);
const SMOKE_ITERATIONS = intEnv('SMOKE_ITERATIONS', 10, 1, 100);
const RATE = intEnv('RATE', 10, 1, MAX_RATE);
const DURATION_S = durationEnv('DURATION', '3m', MAX_DURATION_S);
const PRE_VUS = intEnv('PRE_VUS', Math.min(RATE * 2, MAX_VUS), 1, MAX_VUS);
const VUS_CAP = intEnv('MAX_VUS', Math.min(RATE * 5, MAX_VUS), PRE_VUS, MAX_VUS);
const TOKEN_MARGIN_S = 60;
const ORIGIN_RE = /^https?:\/\/[A-Za-z0-9._-]+(:\d{1,5})?$/;

// Solo esquema, host y puerto: sin ruta, query, fragmento ni credenciales. El mensaje no repite el valor
if (!ORIGIN_RE.test(TARGET)) {
    throw new Error('TARGET_URL debe ser un origen http(s) sin ruta, query, fragmento ni credenciales, p. ej. http://host.docker.internal:8080');
}
if (!['smoke', 'load'].includes(PROFILE)) {
    throw new Error(`PROFILE desconocido: ${PROFILE} (smoke | load)`);
}

const s1Ok = new Rate('s1_ok');
const s1FunctionalErrors = new Counter('s1_functional_errors');
// k6 2.x ya no incluye vus/vus_max en el resumen: VUs ocupados que necesitó la tasa pedida
const vusActive = new Gauge('s1_vus_active');
const authExpired = new Counter('s1_auth_expired');

const scenarios = {
    smoke: {
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: SMOKE_ITERATIONS,
        maxDuration: '1m',
    },
    load: {
        executor: 'constant-arrival-rate',
        rate: RATE,
        timeUnit: '1s',
        duration: `${DURATION_S}s`,
        preAllocatedVUs: PRE_VUS,
        maxVUs: VUS_CAP,
    },
};

export const options = {
    scenarios: { [PROFILE]: scenarios[PROFILE] },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    // Solo umbrales funcionales: no hay un requisito de latencia acordado para la clínica
    thresholds: Object.assign(
        {
            's1_ok': ['rate==1'],
            'http_req_failed{endpoint:s1}': ['rate==0'],
            // Siempre verdadero: solo hace que el resumen muestre la latencia de S1 sin el setup
            'http_req_duration{endpoint:s1}': ['max>=0'],
            'http_reqs{endpoint:s1}': ['count>=0'],
        },
        PROFILE === 'load' ? { 'dropped_iterations': ['count==0'] } : {},
    ),
};

export function setup() {
    const remaining = tokenSecondsRemaining();
    const needed = (PROFILE === 'load' ? DURATION_S : 60) + TOKEN_MARGIN_S;
    if (remaining < needed) {
        fail(`El token vence en ${remaining}s y esta corrida necesita al menos ${needed}s: obtenga uno nuevo justo antes de correr`);
    }

    const res = http.get(
        `${TARGET}/api/doctor/specialties-with-active-doctors?schedulingOrigin=${encodeURIComponent(ORIGIN)}`,
        { headers: authHeaders(), tags: { endpoint: 'discovery' }, timeout: '30s' },
    );
    failOnAuth(res, 'descubrimiento');
    if (res.status !== 200) {
        fail(`Descubrimiento de médicos respondió ${res.status}: no hay médicos activos con agenda o el destino no es el esperado`);
    }
    const doctors = res.json();
    if (!Array.isArray(doctors)) {
        fail('Descubrimiento de médicos no devolvió una lista');
    }

    // Se prueba cada candidato: una fecha agendable puede quedarse sin cupos (hoy ya pasado, todo reservado)
    const pairs = [];
    for (const d of doctors) {
        for (const date of (d.availableDates || []).slice(0, DATES_PER_DOCTOR)) {
            if (pairs.length >= MAX_PAIRS) break;
            const probe = getSlots(d.id, date, 'probe');
            failOnAuth(probe, 'sondeo de cupos');
            if (validSlots(probe).length > 0) {
                pairs.push({ doctorId: d.id, date });
            }
        }
    }
    if (pairs.length === 0) {
        fail(`Sin pares médico/fecha con cupos (${doctors.length} médicos descubiertos). `
            + 'Con los datos de demostración, revise que sigan vigentes (agendas hasta 2026-12-31); no se fabrican datos');
    }

    console.log(`setup: ${doctors.length} médicos, ${pairs.length} pares utilizables, token vence en ${remaining}s`);
    // Nunca devolver el token: setup_data termina en el resumen
    return { doctors: doctors.length, pairs };
}

export default function (data) {
    const pair = data.pairs[exec.scenario.iterationInTest % data.pairs.length];
    vusActive.add(exec.instance.vusActive);
    const res = getSlots(pair.doctorId, pair.date, 's1');

    // Se contabiliza antes de abortar: un 401 también es una solicitud S1 fallida
    const slots = validSlots(res);
    const ok = check(res, {
        'status 200': (r) => r.status === 200,
        'lista de horas no vacía': () => slots.length > 0,
    });
    s1Ok.add(ok);
    if (!ok) {
        s1FunctionalErrors.add(1, { status: String(res.status) });
    }
    if (res.status === 401) {
        authExpired.add(1);
        exec.test.abort('S1 respondió 401: el token venció o fue revocado durante la corrida; resultados no válidos');
    }
}

export function handleSummary(data) {
    const m = data.metrics;
    const val = (name, key) => (m[name] && m[name].values[key] !== undefined ? m[name].values[key] : null);
    const lat = m['http_req_duration{endpoint:s1}'] ? m['http_req_duration{endpoint:s1}'].values : {};
    const s1Count = val('http_reqs{endpoint:s1}', 'count') || 0;
    const dropped = val('dropped_iterations', 'count') || 0;
    const okSamples = m.s1_ok ? m.s1_ok.values.passes + m.s1_ok.values.fails : 0;
    const expected = PROFILE === 'load' ? RATE * DURATION_S : SMOKE_ITERATIONS;
    const thresholdsOk = Object.values(m).every((x) => !x.thresholds
        || Object.values(x.thresholds).every((t) => t.ok));
    const status = runStatus({
        measured: Boolean(data.setup_data),
        authExpired: (val('s1_auth_expired', 'count') || 0) > 0,
        // Interrumpida (p. ej. Ctrl+C o maxDuration): no se procesaron todas las llegadas planeadas.
        // Tolera una llegada en el borde final de la ventana de constant-arrival-rate
        complete: s1Count + dropped >= expected - (PROFILE === 'load' ? 1 : 0),
        thresholdsOk,
    });

    const requested = PROFILE === 'load'
        ? { executor: 'constant-arrival-rate', rate_per_s: RATE, duration_s: DURATION_S, pre_vus: PRE_VUS, max_vus: VUS_CAP,
            expected_iterations: RATE * DURATION_S }
        : { executor: 'per-vu-iterations', vus: 1, iterations: SMOKE_ITERATIONS };

    const result = {
        // no_measurement | invalid | failed | passed (ver runStatus)
        status,
        timestamp: new Date().toISOString(),
        profile: PROFILE,
        scenario: 'S1 GET /api/doctor/{id}/available-slots',
        target: TARGET,
        target_revision: env('META_TARGET_REVISION', 'desconocida'),
        test_revision: env('META_TEST_REVISION', 'desconocida'),
        k6_image: env('META_K6_IMAGE', 'desconocida'),
        dataset: {
            // Lo declara quien corre la prueba: el script no puede saber de dónde vienen los datos del destino
            declared: env('META_DATASET', null),
            scheduling_origin: ORIGIN,
            pairs: data.setup_data ? data.setup_data.pairs.length : 0,
            doctors_discovered: data.setup_data ? data.setup_data.doctors : 0,
        },
        requested,
        generated: {
            s1_requests: s1Count,
            // Sobre la ventana planeada; en smoke no aplica porque no hay tasa objetivo
            s1_rate_per_s: PROFILE === 'load' ? round(s1Count / DURATION_S) : null,
            dropped_iterations: dropped,
            max_vus_active: val('s1_vus_active', 'max'),
        },
        latency_ms: Object.fromEntries(Object.entries(lat).map(([k, v]) => [k, round(v)])),
        errors: {
            http_failed_rate: val('http_req_failed{endpoint:s1}', 'rate'),
            functional_errors: val('s1_functional_errors', 'count') || 0,
            functional_ok_rate: okSamples > 0 ? val('s1_ok', 'rate') : null,
        },
        // Sin medición de S1 los umbrales no dicen nada: null, nunca true
        thresholds_passed: status === 'no_measurement' ? null : thresholdsOk,
        test_run_duration_s: round(data.state.testRunDurationMs / 1000),
    };

    const out = { stdout: textReport(result) };
    const dir = env('RESULTS_DIR', '');
    if (dir) {
        out[`${dir}/result.json`] = JSON.stringify(result, null, 2) + '\n';
    }
    return out;
}

function getSlots(doctorId, date, endpoint) {
    return http.get(`${TARGET}/api/doctor/${doctorId}/available-slots?date=${date}`, {
        headers: authHeaders(),
        // Etiqueta fija: evita una serie por médico/fecha y separa S1 del setup
        tags: { endpoint, name: 'GET /api/doctor/{id}/available-slots' },
        timeout: '10s',
    });
}

// Contrato del controlador: List<LocalTime> serializado como "HH:mm" o "HH:mm:ss"
function validSlots(res) {
    if (res.status !== 200) return [];
    let body;
    try {
        body = res.json();
    } catch (e) {
        return [];
    }
    if (!Array.isArray(body) || !body.every((t) => typeof t === 'string' && /^\d{2}:\d{2}(:\d{2})?$/.test(t))) {
        return [];
    }
    return body;
}

function failOnAuth(res, step) {
    if (res.status === 401) {
        fail(`${step}: 401, token ausente, inválido o vencido`);
    }
    if (res.status === 403) {
        fail(`${step}: 403, el usuario del token no tiene rol SCHEDULER, PATIENT o DOCTOR`);
    }
}

function authHeaders() {
    return { Authorization: `Bearer ${__ENV.ACCESS_TOKEN}`, Accept: 'application/json' };
}

// Solo lee exp para fallar antes de empezar; la validez real la decide el backend
function tokenSecondsRemaining() {
    const token = __ENV.ACCESS_TOKEN || '';
    const parts = token.split('.');
    if (parts.length !== 3) {
        fail('ACCESS_TOKEN ausente o no es un JWT');
    }
    let claims;
    try {
        claims = JSON.parse(encoding.b64decode(parts[1], 'rawurl', 's'));
    } catch (e) {
        fail('ACCESS_TOKEN no tiene un payload JWT legible');
    }
    if (typeof claims.exp !== 'number') {
        fail('ACCESS_TOKEN sin claim exp');
    }
    return Math.floor(claims.exp - Date.now() / 1000);
}

function textReport(r) {
    const l = r.latency_ms;
    const lines = [
        '',
        `== S1 available-slots · perfil ${r.profile} · ${r.timestamp}`,
        `destino ${r.target} (rev. ${r.target_revision}) · prueba rev. ${r.test_revision}`,
        `datos: ${r.dataset.pairs} pares médico/fecha de ${r.dataset.doctors_discovered} médicos (declarados: ${r.dataset.declared || 'no declarado'})`,
        `pedido:   ${JSON.stringify(r.requested)}`,
        `generado: ${r.generated.s1_requests} solicitudes S1`
            + (r.generated.s1_rate_per_s !== null ? ` · ${r.generated.s1_rate_per_s}/s` : '')
            + ` · dropped_iterations ${r.generated.dropped_iterations} · VUs activos máx ${r.generated.max_vus_active}`,
        `latencia ms: avg ${l.avg} · med ${l.med} · p90 ${l['p(90)']} · p95 ${l['p(95)']} · p99 ${l['p(99)']} · max ${l.max}`,
        `errores: http_req_failed ${r.errors.http_failed_rate} · funcionales ${r.errors.functional_errors}`
            + ` · s1_ok ${r.errors.functional_ok_rate}`,
        `umbrales (funcionales y dropped_iterations): ${r.thresholds_passed ? 'OK' : 'FALLARON'}`,
        `estado: ${STATUS_TEXT[r.status]}`,
    ];
    if (r.status === 'no_measurement') {
        return lines.slice(0, 3).concat(`estado: ${STATUS_TEXT[r.status]}`).join('\n') + '\n';
    }
    if (r.generated.dropped_iterations > 0) {
        lines.push('AVISO: el generador no sostuvo la tasa pedida (dropped_iterations > 0); la carga real fue menor');
    }
    return lines.join('\n') + '\n';
}

const STATUS_TEXT = {
    no_measurement: 'SIN MEDICIÓN: la prueba abortó antes de S1 (ver el error de k6)',
    invalid: 'INVÁLIDA: el token venció o la corrida se interrumpió; descarte estos números',
    failed: 'COMPLETA, NO ACEPTADA: fallaron umbrales funcionales o de dropped_iterations',
    passed: 'COMPLETA Y ACEPTADA',
};

function runStatus({ measured, authExpired, complete, thresholdsOk }) {
    if (!measured) return 'no_measurement';
    if (authExpired || !complete) return 'invalid';
    return thresholdsOk ? 'passed' : 'failed';
}

// Detiene toda la prueba (setup o VUs) con un mensaje claro y código de salida distinto de cero
function fail(msg) {
    exec.test.abort(msg);
}

function env(name, def) {
    const v = __ENV[name];
    return v === undefined || v === '' ? def : v;
}

function intEnv(name, def, min, max) {
    const n = Number(env(name, String(def)));
    if (!Number.isInteger(n) || n < min || n > max) {
        throw new Error(`${name} debe ser un entero entre ${min} y ${max}`);
    }
    return n;
}

function durationEnv(name, def, maxS) {
    const m = /^(\d+)(s|m)?$/.exec(env(name, def));
    const s = m ? Number(m[1]) * (m[2] === 'm' ? 60 : 1) : NaN;
    if (!(s > 0 && s <= maxS)) {
        throw new Error(`${name} debe ser una duración como 90s o 3m, de hasta ${maxS}s`);
    }
    return s;
}

function round(n) {
    return Math.round(n * 100) / 100;
}

// Explicit, real-browser acceptance of the generated 2048 application. This does
// not generate code, start/stop servers, import sessions, or mutate state via JS.
// Usage: node scripts/verify-2048-state.mjs http://127.0.0.1:19089 /existing/private/empty/output
import assert from 'node:assert/strict';
import { chmod, lstat, readdir, realpath, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { isDeepStrictEqual } from 'node:util';

const directions = ['left', 'up', 'right', 'down'];
const usage = 'VERIFY_2048_USAGE: expected explicit loopback HTTP origin with port and existing empty private output directory';

export function parseOptions(args) {
  if (args.length !== 2 || !args.every(value => typeof value === 'string' && value.length)) throw new Error(usage);
  let url;
  try { url = new URL(args[0]); } catch { throw new Error(usage); }
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
    || !url.port || url.pathname !== '/' || url.search || url.hash || url.username || url.password) throw new Error(usage);
  return { baseURL: url.origin, output: args[1] };
}

// Independent rules oracle, never imported from generated application code.
export function moveBoard(board, direction) {
  if (!directions.includes(direction)) throw new Error('INVALID_DIRECTION');
  const result = Array(16).fill(0);
  let points = 0;
  for (let line = 0; line < 4; line++) {
    const ids = Array.from({ length: 4 }, (_, p) => direction === 'left' ? line * 4 + p
      : direction === 'right' ? line * 4 + 3 - p : direction === 'up' ? p * 4 + line : (3 - p) * 4 + line);
    const values = ids.map(id => board[id]).filter(Boolean), merged = [];
    for (let index = 0; index < values.length; index++) {
      if (values[index] === values[index + 1]) {
        merged.push(values[index] * 2); points += values[index] * 2; index++;
      } else merged.push(values[index]);
    }
    ids.forEach((id, index) => { result[id] = merged[index] ?? 0; });
  }
  return { board: result, points, changed: result.some((value, index) => value !== board[index]) };
}

const equal = isDeepStrictEqual;

export function acknowledgementMatches(intent, document) {
  return !!intent && Number.isSafeInteger(intent.expectedRevision) && intent.expectedRevision >= 0
    && intent.expectedRevision < Number.MAX_SAFE_INTEGER && intent.stateSchemaVersion === 1
    && !!intent.value && typeof intent.value === 'object' && !Array.isArray(intent.value)
    && !!document && Object.keys(document).sort().join(',') === 'revision,stateSchemaVersion,value'
    && document.revision === intent.expectedRevision + 1 && document.stateSchemaVersion === 1
    && equal(document.value, intent.value);
}

export function classifyWrites(requests, proof) {
  const confirmed = ['hostSaved', 'appSaved', 'noAlerts', 'stable', 'uiMatchesDatabase', 'documentMatchesLastWrite']
    .every(key => proof[key] === true);
  const noConflicts = requests.length > 0 && requests.every(request => request.status === 200);
  const chain = requests.every((request, index) => Number.isSafeInteger(request.expectedRevision)
    && request.expectedRevision >= 0 && (index === 0 || request.expectedRevision === requests[index - 1].expectedRevision + 1));
  const valid = requests.every(request => Number.isFinite(request.finishedAt)
    && ['verified', 'unavailable'].includes(request.bodyEvidence)
    && (!request.failed || request.failureType === 'ABORTED'));
  const directEvidence = requests.every(request => !request.failed && request.bodyEvidence === 'verified');
  const passed = noConflicts && chain && valid && (directEvidence || confirmed);
  return { passed, noConflicts,
    // Never erase or call these anomalies a clean network run, even when the
    // reviewed SDK and persisted document independently confirm the writes.
    transportAnomalies: requests.flatMap((request, index) => request.failed
      ? [{ requestIndex: index, type: request.failureType === 'ABORTED' ? 'ABORTED' : 'OTHER', sdkConfirmed: passed }] : []),
    bodyEvidenceUnavailable: requests.filter(request => request.bodyEvidence === 'unavailable').length };
}

export function visibleBoard(cells) {
  assert.equal(cells.length, 16);
  return cells.map(({ value, text }) => {
    assert.ok(typeof value === 'string' && /^\d+$/.test(value));
    const number = Number(value);
    assert.ok(Number.isSafeInteger(number) && (number === 0 || (number >= 2 && Number.isInteger(Math.log2(number)))));
    const rendered = text.trim();
    assert.ok(rendered === String(number) || (number === 0 && rendered === ''), 'Visible tile text must match data-value');
    return number;
  });
}

// Request objects are retained only in memory and never serialized. Tracking
// finishes on the response body or request failure, not on response headers.
export function createWriteTracker(clock = Date.now) {
  const requests = new Map();
  const intents = new Map();
  let completedMoves = 0;
  return {
    begin(key, body) {
      assert.ok(!requests.has(key));
      if (body && typeof body === 'object') intents.set(key, structuredClone({ value: body.value,
        expectedRevision: body.expectedRevision, stateSchemaVersion: body.stateSchemaVersion }));
      requests.set(key, { startedAt: clock(), finishedAt: null, status: null, failed: false,
        failureType: null, expectedRevision: Number.isSafeInteger(body?.expectedRevision) ? body.expectedRevision : null,
        bodyEvidence: 'pending', movesDuringFlight: 0 });
    },
    acknowledge(key, status) { if (requests.has(key)) requests.get(key).status = status; },
    finish(key, failed = false, failureType = null) {
      if (!requests.has(key)) return;
      const request = requests.get(key);
      Object.assign(request, { finishedAt: clock(), failed,
        failureType: failed ? failureType === 'ABORTED' ? 'ABORTED' : 'OTHER' : null });
      if (request.status === null) request.bodyEvidence = 'unavailable';
    },
    body(key, document, unavailable = false) {
      if (requests.has(key)) requests.get(key).bodyEvidence = unavailable ? 'unavailable'
        : acknowledgementMatches(intents.get(key), document) ? 'verified' : 'invalid';
    },
    matchesLastWrite(document) { return acknowledgementMatches(intents.get([...requests.keys()].at(-1)), document); },
    move(startedAt) {
      completedMoves++;
      for (const request of requests.values()) {
        if (request.startedAt <= startedAt && (request.finishedAt === null || startedAt < request.finishedAt)) request.movesDuringFlight++;
      }
    },
    snapshot() {
      const records = [...requests.values()].map(value => ({ ...value }));
      const finished = records.filter(value => value.finishedAt !== null).length;
      return { started: records.length, finished, inFlight: records.length - finished,
        pendingBodies: records.filter(record => record.bodyEvidence === 'pending').length, completedMoves, requests: records };
    },
  };
}

export function createStabilityProbe(quietMs = 1000) {
  let previous, since;
  return {
    observe(document, writes, now) {
      if (writes.inFlight !== 0 || (writes.pendingBodies ?? 0) !== 0) { previous = undefined; since = undefined; return false; }
      const sample = { document, started: writes.started, finished: writes.finished };
      if (!equal(previous, sample)) {
        previous = structuredClone(sample); since = now;
        return false;
      }
      return now - since >= quietMs;
    },
  };
}

export function restorationUnchanged(before, after, writesBefore, writesAfter) {
  return equal(before, after) && writesAfter.started === writesBefore && writesAfter.inFlight === 0;
}

async function readGame(frame) {
  const board = visibleBoard(await frame.getByRole('gridcell').evaluateAll(cells => cells.map(cell => ({
    value: cell.getAttribute('data-value'), text: cell.innerText,
  }))));
  const score = Number(await frame.getByLabel('当前分数', { exact: true }).innerText());
  const best = Number(await frame.getByLabel('最高分', { exact: true }).innerText());
  assert.ok(Number.isSafeInteger(score) && score >= 0 && Number.isSafeInteger(best) && best >= score);
  return { board, score, best };
}

async function ready(frame, expect) {
  await expect(frame.getByRole('grid', { name: '2048棋盘', exact: true })).toBeVisible({ timeout: 25000 });
  await expect.poll(() => frame.getByRole('gridcell').evaluateAll(cells => cells.length === 16
    && cells.every(cell => /^\d+$/.test(cell.getAttribute('data-value') ?? ''))
    && cells.some(cell => Number(cell.getAttribute('data-value')) > 0)), { timeout: 15000 }).toBe(true);
}

async function persistedGame(context, baseURL) {
  // This context belongs solely to this test. Only GET is used; no credential
  // inspection, browser JS request, or synthetic state injection is involved.
  const response = await context.request.get(baseURL + '/api/state', { timeout: 5000, maxRedirects: 0 });
  assert.equal(response.status(), 200);
  const document = await response.json();
  const value = document.value;
  assert.ok(Number.isSafeInteger(document.revision) && document.revision >= 0 && document.stateSchemaVersion === 1);
  return { document, revision: document.revision, game: {
    board: Array.isArray(value?.board) ? value.board.flat() : null,
    score: value?.score ?? null, best: value?.best ?? null,
  } };
}

async function waitPersisted(context, baseURL, tracker, game) {
  const deadline = Date.now() + 10000;
  const stability = createStabilityProbe();
  let persisted, settled = false, matched = false;
  do {
    persisted = await persistedGame(context, baseURL);
    matched = game === undefined || (persisted.revision > 0 && equal(persisted.game, game));
    settled = stability.observe(persisted.document, tracker.snapshot(), Date.now());
    if (settled && matched) return { matched, settled, ...persisted };
    await delay(150);
  } while (Date.now() < deadline);
  return { matched, settled, ...persisted };
}

async function screenshot(page, output, name) {
  const path = join(output, name + '.png');
  await page.screenshot({ path, fullPage: true });
  await chmod(path, 0o600);
}

function observe(page, baseURL) {
  const tracker = createWriteTracker();
  const observation = { consoleErrors: 0, consoleWarnings: 0, pageErrors: 0 };
  const isWrite = request => request.method() === 'PUT' && request.url() === baseURL + '/api/state';
  page.on('pageerror', () => observation.pageErrors++);
  page.on('console', message => {
    if (message.type() === 'error') observation.consoleErrors++;
    if (message.type() === 'warning') observation.consoleWarnings++;
  });
  page.on('request', request => {
    if (!isWrite(request)) return;
    let body;
    try { body = request.postDataJSON(); } catch { /* Invalid intent is never confirmed. */ }
    tracker.begin(request, body);
  });
  page.on('response', response => {
    if (!isWrite(response.request())) return;
    tracker.acknowledge(response.request(), response.status());
    void probeResponseBody(response, tracker);
  });
  page.on('requestfinished', request => { if (isWrite(request)) tracker.finish(request); });
  page.on('requestfailed', request => {
    if (isWrite(request)) tracker.finish(request, true, request.failure()?.errorText === 'net::ERR_ABORTED' ? 'ABORTED' : 'OTHER');
  });
  return { observation, tracker };
}

async function probeResponseBody(response, tracker) {
  let timer;
  try {
    // Response.finished() can remain pending on Chromium's loadingFailed path.
    // Body inspection is bounded independently and never prints raw errors/data.
    const body = await Promise.race([response.json(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('BODY_EVIDENCE_TIMEOUT')), 2000);
    })]);
    tracker.body(response.request(), body);
  } catch (error) {
    tracker.body(response.request(), null, !(error instanceof SyntaxError));
  } finally { clearTimeout(timer); }
}

async function confirmation(page, frame, tracker, saved, game) {
  const panel = page.locator('.runtime-panel');
  const statuses = await panel.getByRole('status').allTextContents();
  const saveState = frame.locator('#saveState');
  const writes = tracker.snapshot();
  return {
    hostSaved: statuses.some(text => /^已保存到数据库[。]?$/.test(text.trim())),
    appSaved: await saveState.count() === 1 && (await saveState.innerText()).trim() === '已保存',
    noAlerts: await panel.getByRole('alert').count() === 0 && await frame.getByRole('alert').count() === 0,
    stable: saved.settled && writes.inFlight === 0 && writes.pendingBodies === 0,
    uiMatchesDatabase: equal(game, saved.game),
    documentMatchesLastWrite: tracker.matchesLastWrite(saved.document),
  };
}

async function reloadWithoutWriting({ page, frame, expect, context, baseURL, tracker, saved }) {
  const writesBefore = tracker.snapshot().started;
  await page.reload({ waitUntil: 'domcontentloaded' });
  await ready(frame, expect);
  const restored = await waitPersisted(context, baseURL, tracker);
  const game = await readGame(frame);
  return { game, revision: restored.revision, settled: restored.settled,
    unchanged: restored.settled && restorationUnchanged(saved.document, restored.document, writesBefore, tracker.snapshot()),
    startupPuts: tracker.snapshot().started - writesBefore,
    uiMatchesDatabase: equal(game, restored.game) };
}

async function initialStateCase({ browser, expect, baseURL, output }) {
  const context = await browser.newContext({ viewport: { width: 1100, height: 1000 } });
  const page = await context.newPage(), frame = page.frameLocator('iframe');
  const { observation, tracker } = observe(page, baseURL);
  try {
    await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
    await ready(frame, expect);
    const before = await readGame(frame);
    // Revision 0 is only template fallback, not evidence of a database write.
    const saved = await waitPersisted(context, baseURL, tracker, before);
    const afterSettled = await readGame(frame);
    const sdkConfirmation = await confirmation(page, frame, tracker, saved, afterSettled);
    const writeResult = classifyWrites(tracker.snapshot().requests, sdkConfirmation);
    await screenshot(page, output, 'initial-before-reload');
    const restored = await reloadWithoutWriting({ page, frame, expect, context, baseURL, tracker, saved });
    await screenshot(page, output, 'initial-after-reload');
    return { name: 'first-board-persisted-without-input', passed: saved.matched && saved.settled && writeResult.passed
      && sdkConfirmation.appSaved && sdkConfirmation.noAlerts && sdkConfirmation.documentMatchesLastWrite
      && equal(before, afterSettled) && equal(before, restored.game) && restored.unchanged && restored.uiMatchesDatabase
      && observation.pageErrors === 0 && observation.consoleErrors === 0 && observation.consoleWarnings === 0,
      initialBoardPersisted: saved.matched, writesSettled: saved.settled, before, afterSettled, after: restored.game,
      reloadPreserved: equal(before, restored.game), reloadFullDocumentUnchanged: restored.unchanged,
      reloadStartupPuts: restored.startupPuts, persistedRevision: saved.revision, reloadedRevision: restored.revision,
      writes: tracker.snapshot(), sdkConfirmation, transportAnomalies: writeResult.transportAnomalies,
      bodyEvidenceUnavailable: writeResult.bodyEvidenceUnavailable, ...observation };
  } finally { await context.close(); }
}

async function rapidInputCase({ browser, expect, baseURL, output }) {
  // Independent visitor: failure of initial persistence cannot mask this case.
  const context = await browser.newContext({ viewport: { width: 1100, height: 1000 } });
  const page = await context.newPage(), frame = page.frameLocator('iframe');
  const { observation, tracker } = observe(page, baseURL);
  try {
    await page.goto(baseURL, { waitUntil: 'domcontentloaded' });
    await ready(frame, expect);
    // A stable revision 0 is accepted here so the independent rapid-input case
    // still exercises the old application whose first render never saves.
    const initial = await waitPersisted(context, baseURL, tracker);
    assert.ok(initial.settled, 'Initialization writes must settle before rapid input');
    const writesBeforeInput = tracker.snapshot().started;
    let firstIntercepted = false;
    await page.route(baseURL + '/api/state', async route => {
      if (route.request().method() === 'PUT') { firstIntercepted = true; await delay(500); }
      await route.continue();
    });
    await frame.getByRole('grid', { name: '2048棋盘', exact: true }).focus();
    const started = Date.now();
    const moves = [];
    for (let index = 0; index < 8; index++) {
      const before = await readGame(frame);
      const ordered = [...directions.slice(index % 4), ...directions.slice(0, index % 4)];
      const direction = ordered.find(value => moveBoard(before.board, value).changed);
      assert.ok(direction, 'A valid move is needed for this acceptance case');
      const expected = moveBoard(before.board, direction);
      const inputStarted = Date.now();
      await page.keyboard.press('Arrow' + direction[0].toUpperCase() + direction.slice(1));
      const after = await readGame(frame);
      const inserted = after.board.map((value, cell) => ({ value, cell }))
        .filter(({ value, cell }) => value !== expected.board[cell]);
      assert.equal(inserted.length, 1, 'Each accepted move must add exactly one tile');
      assert.equal(expected.board[inserted[0].cell], 0);
      assert.ok([2, 4].includes(inserted[0].value));
      assert.equal(after.score, before.score + expected.points);
      tracker.move(inputStarted);
      moves.push({ direction, gained: expected.points });
      // Do not mistake a debounce that sends only after all inputs for an
      // in-flight save queue. First trigger a real PUT, then keep interacting.
      if (index === 0) await expect.poll(() => firstIntercepted, { timeout: 5000 }).toBe(true);
    }
    const inputDurationMs = Date.now() - started, beforeReload = await readGame(frame);
    const saved = await waitPersisted(context, baseURL, tracker, beforeReload);
    const afterSettled = await readGame(frame);
    const allWrites = tracker.snapshot().requests;
    const inputWrites = allWrites.slice(writesBeforeInput);
    const sdkConfirmation = await confirmation(page, frame, tracker, saved, afterSettled);
    const writeResult = classifyWrites(allWrites, sdkConfirmation);
    await screenshot(page, output, 'rapid-before-reload');
    const restored = await reloadWithoutWriting({ page, frame, expect, context, baseURL, tracker, saved });
    await screenshot(page, output, 'rapid-after-reload');
    const genuinelyOverlapped = inputWrites[0]?.movesDuringFlight >= 2;
    return { name: 'rapid-input-latest-state-persisted', passed: saved.matched && saved.settled
      && equal(beforeReload, afterSettled) && equal(beforeReload, restored.game) && restored.unchanged && restored.uiMatchesDatabase
      && writeResult.passed && Object.values(sdkConfirmation).every(value => value === true)
      && genuinelyOverlapped && observation.pageErrors === 0 && observation.consoleErrors === 0 && observation.consoleWarnings === 0,
      latencyMs: 500, inputDurationMs, moves, latestStatePersisted: saved.matched, writesSettled: saved.settled,
      reloadPreserved: equal(beforeReload, restored.game), noConflicts: writeResult.noConflicts, genuinelyOverlapped,
      beforeReload, afterSettled, afterReload: restored.game, persistedRevision: saved.revision, reloadedRevision: restored.revision,
      reloadFullDocumentUnchanged: restored.unchanged, reloadStartupPuts: restored.startupPuts,
      inputWrites, allWrites, sdkConfirmation, transportAnomalies: writeResult.transportAnomalies,
      bodyEvidenceUnavailable: writeResult.bodyEvidenceUnavailable, ...observation };
  } finally { await context.close(); }
}

export async function verify2048State({ baseURL, output }) {
  const directory = await lstat(output);
  if (!directory.isDirectory() || directory.isSymbolicLink() || (directory.mode & 0o077)
    || (await readdir(output)).length) throw new Error(usage);
  output = await realpath(output);
  const { chromium, expect } = await import('../frontend/node_modules/@playwright/test/index.mjs');
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const cases = [];
  try {
    for (const runCase of [initialStateCase, rapidInputCase]) {
      try { cases.push(await runCase({ browser, expect, baseURL, output })); }
      catch (error) {
        // Never serialize arbitrary browser/network error text or credential data.
        cases.push({ name: runCase.name, passed: false, errorType: error instanceof assert.AssertionError ? 'ASSERTION_FAILED' : 'EXECUTION_FAILED' });
      }
    }
  } finally { await browser.close(); }
  const result = { baseURL, checkedAt: new Date().toISOString(), modelCalls: 0,
    passed: cases.every(value => value.passed), cases };
  await writeFile(join(output, 'result.json'), JSON.stringify(result, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  console.log(JSON.stringify(result));
  return result;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const result = await verify2048State(parseOptions(process.argv.slice(2)));
    if (!result.passed) process.exitCode = 1;
  } catch (error) {
    console.error(error.message === usage ? usage : 'VERIFY_2048_FAILED: no model calls; no server process changed');
    process.exitCode = 1;
  }
}

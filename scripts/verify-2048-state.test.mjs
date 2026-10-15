import assert from 'node:assert/strict';
import test from 'node:test';
import { parseOptions, moveBoard, visibleBoard, createWriteTracker, createStabilityProbe,
  restorationUnchanged, acknowledgementMatches, classifyWrites } from './verify-2048-state.mjs';

test('requires an explicit loopback HTTP origin and output directory', () => {
  assert.deepEqual(parseOptions(['http://127.0.0.1:19089/', '/tmp/evidence']), {
    baseURL: 'http://127.0.0.1:19089', output: '/tmp/evidence',
  });
  for (const input of [[], ['http://127.0.0.1:19089'],
    ['http://example.com:19089', '/tmp/evidence'],
    ['http://127.0.0.1:19089/app', '/tmp/evidence'],
    ['http://user:password@127.0.0.1:19089', '/tmp/evidence'],
    ['http://127.0.0.1:19089/?secret=123', '/tmp/evidence'],
    ['http://127.0.0.1:19089/#fragment', '/tmp/evidence'],
    ['http://127.0.0.1', '/tmp/evidence'],
    ['http://127.0.0.1:19089', '/tmp/evidence', '--skip-checks']]) {
    assert.throws(() => parseOptions(input), /VERIFY_2048_USAGE/);
  }
});

test('independent move oracle merges each tile once and accounts for score', () => {
  const board = [2, 2, 4, 4, 2, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0];
  const result = moveBoard(board, 'left');
  assert.deepEqual(result, {
    board: [4, 8, 0, 0, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], points: 20, changed: true,
  });
  assert.deepEqual(board.slice(0, 4), [2, 2, 4, 4]);
  assert.deepEqual(moveBoard(board, 'right').board.slice(0, 8), [0, 0, 4, 8, 0, 0, 4, 4]);
});

test('independent move oracle handles vertical directions and immovable boards', () => {
  const board = [2, 0, 0, 0, 2, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0];
  assert.deepEqual(moveBoard(board, 'up').board, [4, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual(moveBoard(board, 'down').board, [0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0]);
  assert.equal(moveBoard([2, 4, 8, 16, ...Array(12).fill(0)], 'left').changed, false);
  assert.throws(() => moveBoard(board, 'diagonal'), /INVALID_DIRECTION/);
});

test('visible board rejects missing attributes and mismatched rendered tile text', () => {
  const cells = Array.from({ length: 16 }, (_, index) => ({ value: index === 0 ? '2' : '0', text: index === 0 ? ' 2 ' : '' }));
  assert.deepEqual(visibleBoard(cells), [2, ...Array(15).fill(0)]);
  for (const wrong of [{ value: '2', text: '4' }, { value: '2', text: '' },
    { value: null, text: '' }, { value: '3', text: '3' }, { value: '0', text: '2' }]) {
    assert.throws(() => visibleBoard([wrong, ...cells.slice(1)]));
  }
  assert.throws(() => visibleBoard(cells.slice(1)));
});

test('write tracker counts only effective input begun during an intercepted request', () => {
  let now = 0;
  const tracker = createWriteTracker(() => now);
  tracker.move(0); // A debounced write issued after all input must not look overlapped.
  now = 10; tracker.begin('first');
  now = 20; tracker.move(5);
  now = 30; tracker.move(15);
  now = 40; tracker.acknowledge('first', 200);
  assert.equal(tracker.snapshot().inFlight, 1, 'Headers alone do not finish the response');
  now = 50; tracker.finish('first');
  now = 60; tracker.move(55);
  const result = tracker.snapshot();
  assert.equal(result.completedMoves, 4);
  assert.equal(result.requests[0].movesDuringFlight, 1);
  assert.deepEqual(result.requests[0], { startedAt: 10, finishedAt: 50, status: 200, failed: false,
    failureType: null, expectedRevision: null, bodyEvidence: 'pending', movesDuringFlight: 1 });
  assert.equal(result.inFlight, 0);
  assert.equal(result.started, 1);
  assert.equal(result.finished, 1);
  now = 70; tracker.begin('second');
  now = 80; tracker.finish('second', true);
  assert.equal(tracker.snapshot().requests[1].failed, true);
});

test('persistence stability requires a quiet interval, no in-flight writes and unchanged full data', () => {
  const stable = createStabilityProbe(1000);
  const document = { value: { board: [[2]], score: 0, best: 0, won: false }, revision: 1, stateSchemaVersion: 1 };
  const idle = { inFlight: 0, started: 1, finished: 1 };
  assert.equal(stable.observe(document, idle, 0), false);
  assert.equal(stable.observe(document, idle, 999), false);
  assert.equal(stable.observe(document, idle, 1000), true);
  assert.equal(stable.observe(document, { inFlight: 1, started: 2, finished: 1 }, 1100), false);
  assert.equal(stable.observe(document, { inFlight: 0, started: 2, finished: 2 }, 1200), false);
  assert.equal(stable.observe(document, { inFlight: 0, started: 2, finished: 2 }, 2200), true);
  assert.equal(stable.observe({ ...document, revision: 2 }, { inFlight: 0, started: 2, finished: 2 }, 2300), false);
  const changed = structuredClone(document);
  const fullData = createStabilityProbe(1000);
  assert.equal(fullData.observe(changed, idle, 0), false);
  changed.value.won = true; // A visually identical board can still have different stored flags.
  assert.equal(fullData.observe(changed, idle, 1000), false);
  assert.equal(fullData.observe(changed, idle, 2000), true);
});

test('reload must preserve the entire document and may not issue a startup write', () => {
  const before = { value: { board: [[2]], score: 0, best: 0, won: false }, revision: 1, stateSchemaVersion: 1 };
  assert.equal(restorationUnchanged(before, structuredClone(before), 1, { started: 1, inFlight: 0 }), true);
  assert.equal(restorationUnchanged(before, { ...before, revision: 2 }, 1, { started: 1, inFlight: 0 }), false);
  assert.equal(restorationUnchanged(before, { ...before, value: { ...before.value, won: true } }, 1, { started: 1, inFlight: 0 }), false);
  assert.equal(restorationUnchanged(before, before, 1, { started: 2, inFlight: 0 }), false);
  assert.equal(restorationUnchanged(before, before, 1, { started: 1, inFlight: 1 }), false);
});

const acknowledged = (revision, overrides = {}) => ({ expectedRevision: revision, status: 200,
  finishedAt: 100, failed: false, failureType: null, bodyEvidence: 'verified', ...overrides });
const proof = { hostSaved: true, appSaved: true, noAlerts: true, stable: true,
  uiMatchesDatabase: true, documentMatchesLastWrite: true };

test('acknowledgement matches the entire submitted value, exact next revision and schema', () => {
  const intent = { value: { board: [[2]], score: 0, won: false }, expectedRevision: 2, stateSchemaVersion: 1 };
  const document = { value: structuredClone(intent.value), revision: 3, stateSchemaVersion: 1 };
  assert.equal(acknowledgementMatches(intent, document), true);
  for (const changed of [null, { ...document, revision: 2 }, { ...document, stateSchemaVersion: 2 },
    { ...document, value: { ...document.value, won: true } }, { ...document, extra: true }]) {
    assert.equal(acknowledgementMatches(intent, changed), false);
  }
  assert.equal(acknowledgementMatches(undefined, document), false);
});

test('200 plus ABORTED requires dual SDK confirmation and exact persisted state; anomaly remains reported', () => {
  const records = [acknowledged(1), acknowledged(2, { failed: true, failureType: 'ABORTED', bodyEvidence: 'unavailable' })];
  const result = classifyWrites(records, proof);
  assert.equal(result.passed, true);
  assert.equal(result.transportAnomalies.length, 1);
  assert.equal(result.bodyEvidenceUnavailable, 1);
  for (const key of Object.keys(proof)) {
    assert.equal(classifyWrites(records, { ...proof, [key]: false }).passed, false, key);
  }
  assert.equal(classifyWrites(records, {}).passed, false);
});

test('intermediate ABORTED is accepted only with an exact sequential acknowledgement chain', () => {
  const records = [acknowledged(1, { failed: true, failureType: 'ABORTED', bodyEvidence: 'unavailable' }), acknowledged(2)];
  assert.equal(classifyWrites(records, proof).passed, true);
  assert.equal(classifyWrites([records[0], acknowledged(1)], proof).passed, false);
  assert.equal(classifyWrites([records[0], acknowledged(3)], proof).passed, false);
  assert.equal(classifyWrites(records, { ...proof, hostSaved: false }).passed, false);
});

test('real conflicts, other failures, invalid bodies and unconfirmed requests never receive an exception', () => {
  for (const changed of [{ status: 409 }, { status: 500 }, { status: null },
    { failed: true, failureType: 'OTHER' }, { failed: true, failureType: null },
    { bodyEvidence: 'invalid' }, { bodyEvidence: 'pending' }, { finishedAt: null }, { expectedRevision: null }]) {
    assert.equal(classifyWrites([acknowledged(1, changed)], proof).passed, false, JSON.stringify(changed));
  }
  assert.equal(classifyWrites([], proof).passed, false);
  assert.equal(classifyWrites([acknowledged(1, { bodyEvidence: 'unavailable' })], {}).passed, false);
  assert.equal(classifyWrites([acknowledged(1)], {}).passed, true);
});

test('write tracker snapshots exclude private business data and wait for response-body evidence', () => {
  const tracker = createWriteTracker(() => 1);
  const body = { value: { board: [[2]], privateField: 'BUSINESS_CANARY' }, expectedRevision: 1, stateSchemaVersion: 1 };
  tracker.begin('request', body);
  tracker.acknowledge('request', 200);
  tracker.finish('request', true, 'ABORTED');
  assert.equal(tracker.snapshot().pendingBodies, 1);
  const document = { value: structuredClone(body.value), revision: 2, stateSchemaVersion: 1 };
  body.value.board[0][0] = 4;
  tracker.body('request', document);
  assert.equal(tracker.matchesLastWrite(document), true);
  assert.equal(tracker.snapshot().pendingBodies, 0);
  assert.equal(tracker.snapshot().requests[0].bodyEvidence, 'verified');
  assert.equal(JSON.stringify(tracker.snapshot()).includes('BUSINESS_CANARY'), false);
  assert.equal(createStabilityProbe().observe(document, { inFlight: 0, pendingBodies: 1, started: 1, finished: 1 }, 1), false);
});

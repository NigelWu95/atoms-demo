// Trusted bootstrap. Artifact execution is gated for bridge-enabled documents.
// https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage
function startAtomsRuntime(launch) {
    'use strict';
    const protocol = 'atoms-runtime-v1';
    const url = new URL(window.location.href);
    const channelId = new URLSearchParams(url.hash.slice(1)).get('atoms-channel');
    const hostOrigin = url.origin;
    const pending = new Map();
    let port = null, disposed = false, greeted = false, called = false, intent = null;
    let resolveConnection, rejectConnection;
    const connection = new Promise((resolve, reject) => { resolveConnection = resolve; rejectConnection = reject; });
    connection.catch(() => {});
    const failure = (code, message) => Object.assign(new Error(message), { code });
    // This standalone sandbox script cannot import the host's UUID helper.
    // getRandomValues also works in HTTP contexts; retain cryptographic entropy.
    function randomId() {
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
    let deadline = channelId ? setTimeout(dispose, 15000) : null;
    function dispose() {
        if (disposed) return;
        disposed = true;
        clearTimeout(deadline);
        window.removeEventListener('message', handshake);
        const error = failure('RUNTIME_DISPOSED', '预览已关闭或连接超时，请重新加载。');
        rejectConnection(error);
        for (const item of pending.values()) { clearTimeout(item.timer); item.reject(error); }
        pending.clear();
        if (port) { port.onmessage = null; port.close(); }
    }
    function send(type, payload, requestId = randomId()) {
        if (port && !disposed) port.postMessage({ protocol, channelId, requestId, type, payload });
    }
    function rpc(type, payload, requestId = randomId()) {
        if (!port || disposed) return Promise.reject(failure('RUNTIME_DISPOSED', '预览连接不可用。'));
        if (pending.size >= 16 || pending.has(requestId)) return Promise.reject(failure('RPC_BUSY', '请等待当前操作完成。'));
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                pending.delete(requestId);
                reject(failure('RPC_TIMEOUT', '操作结果未知，请使用相同内容重试保存。'));
            }, 15000);
            pending.set(requestId, { resolve, reject, timer });
            send(type, payload, requestId);
        });
    }
    function receive(event) {
        const data = event.data;
        if (!data || data.protocol !== protocol || data.channelId !== channelId) return;
        if (data.type === 'DISPOSE') { dispose(); return; }
        if (data.type !== 'RESULT' || !pending.has(data.requestId)) return;
        const item = pending.get(data.requestId);
        clearTimeout(item.timer); pending.delete(data.requestId);
        if (data.ok === true && data.result && typeof data.result.value === 'object' && data.result.value !== null && !Array.isArray(data.result.value)
                && Number.isSafeInteger(data.result.revision) && data.result.revision >= 0 && data.result.stateSchemaVersion === 1) item.resolve(data.result);
        else if (data.ok === false && data.error && typeof data.error.code === 'string' && typeof data.error.message === 'string') item.reject(failure(data.error.code, data.error.message));
        else item.reject(failure('INVALID_RESPONSE', '保存结果无法确认，请用相同内容重试。'));
    }
    const sdk = Object.freeze({ storage: Object.freeze({
        get: () => rpc('STATE_GET', {}),
        async save(value, expectedRevision) {
            if (!value || typeof value !== 'object' || Array.isArray(value) || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0)
                throw failure('INVALID_INPUT', '状态必须为 JSON 对象，修订号必须有效。');
            const snapshot = JSON.stringify({ value, expectedRevision });
            if (intent && intent.snapshot !== snapshot) throw failure('SAVE_UNRESOLVED', '上一笔保存结果未知，请先重试原内容。');
            if (!intent && pending.size >= 16) throw failure('RPC_BUSY', '请等待当前操作完成。');
            if (!intent) intent = { snapshot, requestId: randomId() };
            const current = intent;
            try {
                const result = await rpc('STATE_SAVE', JSON.parse(snapshot), current.requestId);
                if (intent === current) intent = null;
                return result;
            } catch (error) {
                // Network, timeout and malformed 2xx responses are ambiguous, not a failed transaction.
                if (['STATE_CONFLICT', 'STALE_APP_VERSION', 'READ_ONLY_PREVIEW', 'INVALID_INPUT', 'IDEMPOTENCY_PAYLOAD_MISMATCH'].includes(error.code) && intent === current) intent = null;
                throw error;
            }
        },
    }) });
    const reportError = (error) => send('RUNTIME_ERROR', { message: String(error?.message || '应用发生运行错误').slice(0, 1000) });
    Object.defineProperty(window, 'Atoms', { configurable: false, writable: false, value: Object.freeze({
        ready(callback) {
            if (called || typeof callback !== 'function') { reportError(new Error('Atoms.ready 只能使用一次函数回调。')); return; }
            called = true;
            if (!deadline && !port) deadline = setTimeout(dispose, 15000);
            connection.then(() => callback(sdk)).then(() => send('RUNTIME_READY', {})).catch(reportError);
        },
    }) });
    function handshake(event) {
        const data = event.data;
        if (disposed || port || window.parent === window || event.source !== window.parent || event.origin !== hostOrigin
                || !channelId || !data || Object.keys(data).length !== 3 || data.protocol !== protocol || data.channelId !== channelId) return;
        if (data.type === 'INIT' && !greeted) {
            greeted = true;
            window.parent.postMessage({ protocol, type: 'HELLO', channelId }, hostOrigin);
        } else if (data.type === 'CONNECT' && greeted && event.ports.length === 1) {
            port = event.ports[0]; port.onmessage = receive; port.start();
            clearTimeout(deadline);
            window.removeEventListener('message', handshake);
            resolveConnection();
            launch();
        }
    }
    window.addEventListener('message', handshake);
    window.addEventListener('pagehide', dispose);
    window.addEventListener('error', (event) => reportError(event.error || new Error(event.message)));
    window.addEventListener('unhandledrejection', (event) => reportError(event.reason));
    // Direct/legacy documents retain sandboxing but do not receive a host data bridge.
    if (!channelId) launch();
}

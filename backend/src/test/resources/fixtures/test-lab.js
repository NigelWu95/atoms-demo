'use strict';
(() => {
    const button = document.querySelector('#create');
    const status = document.querySelector('#status');
    const error = document.querySelector('#error');
    const selector = document.querySelector('#fixture');
    const direct = document.querySelector('#direct');
    let csrf;
    let intent;
    let intentFixture;
    const request = async (url, method = 'GET', body, key) => {
        const headers = {};
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (method !== 'GET' && csrf) headers['X-CSRF-TOKEN'] = csrf;
        if (key) headers['Idempotency-Key'] = key;
        const response = await fetch(url, { method, headers, credentials: 'same-origin', cache: 'no-store',
            signal: AbortSignal.timeout(10000), body: body === undefined ? undefined : JSON.stringify(body) });
        const result = await response.json();
        if (!response.ok) throw new Error(result.error?.message ?? '测试请求失败，请重试。');
        return result;
    };
    button.addEventListener('click', async () => {
        if (button.disabled) return;
        button.disabled = true;
        error.textContent = '';
        status.textContent = '正在保存测试版本…';
        try {
            csrf = (await request('/api/csrf')).token;
            await request('/api/session', 'POST', {});
            csrf = (await request('/api/csrf')).token;
            if (!intent || intentFixture !== selector.value) {
                intent = crypto.randomUUID();
                intentFixture = selector.value;
            }
            const result = await request('/api/test-fixtures', 'POST', { fixture: intentFixture }, intent);
            const history = intentFixture === 'history';
            const version = history ? result.firstVersionId : result.version.id;
            const url = `/api/projects/${encodeURIComponent(result.project.id)}/versions/${encodeURIComponent(version)}/document?previewMode=${history ? 'HISTORY' : 'CURRENT'}`;
            const frame = document.createElement('iframe');
            frame.title = '受限测试预览';
            frame.setAttribute('sandbox', 'allow-scripts');
            frame.referrerPolicy = 'no-referrer';
            frame.src = url;
            document.querySelector('#preview').replaceChildren(frame);
            direct.href = url;
            direct.hidden = false;
            status.textContent = history ? '测试版本已保存 · 正在展示历史示例（V1），当前为 V2' : '测试版本已保存 · 正在展示 V1（无业务数据保存）';
            intent = undefined;
        } catch (failure) {
            status.textContent = '装载未完成。重试将复用本次操作标识。';
            error.textContent = failure instanceof Error ? failure.message : '装载失败。';
        } finally { button.disabled = false; }
    });
})();

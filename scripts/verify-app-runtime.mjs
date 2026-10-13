import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  mkdir,
  mkdtemp,
  readFile,
  writeFile,
  realpath,
  copyFile,
} from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { startOwned, assertFree } from "./standalone-process.mjs";
import { toolEnvironment } from "../templates/standalone-v1/scripts/process.mjs";
import { verifyInstanceBrowser } from "./verify-instance-browser.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const origin = "http://127.0.0.1:8081";
const config = join(root, ".local/standalone");
const browserOnly = process.argv[3] === "--browser-only";
if (process.argv[3] && !browserOnly)
  throw new Error("Unknown verification mode");
const work = process.argv[2]
  ? await realpath(process.argv[2])
  : await mkdtemp(join(config, "instance-verification-"));
const builds = join(work, "builds"),
  runtimes = join(work, "runtimes");
for (const directory of [builds, runtimes])
  await mkdir(directory, { mode: 0o700, recursive: true });
console.log("Runtime verification directory: " + work);
await assertFree(origin);
const env = toolEnvironment(),
  java = join(env.JAVA_HOME, "bin/java");
const platformJar = join(work, "platform-" + randomUUID() + ".jar");
await copyFile(
  join(root, "backend/target/atoms-demo-0.1.0-SNAPSHOT.jar"),
  platformJar,
);
const platform = await startOwned(
  java,
  [
    "-Xmx384m",
    "-Dloader.path=" + join(root, "backend/target/test-classes"),
    "-Dloader.main=dev.atoms.demo.instances.InstanceFixture",
    "-cp",
    platformJar,
    "org.springframework.boot.loader.launch.PropertiesLauncher",
    builds,
    runtimes,
    process.execPath,
    java,
    config,
  ],
  { cwd: join(root, "backend"), env, baseURL: origin },
);
const { request } =
  await import("../frontend/node_modules/playwright/index.mjs");
let storageState;
try {
  storageState = JSON.parse(await readFile(join(work, "session.json"), "utf8"));
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
const api = await request.newContext({
  baseURL: origin,
  timeout: 15000,
  storageState,
});
let data;
try {
  data = JSON.parse(await readFile(join(work, "projects.json"), "utf8"));
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
async function persist() {
  await writeFile(
    join(work, "session.json"),
    JSON.stringify(await api.storageState()),
    { mode: 0o600 },
  );
  await writeFile(join(work, "projects.json"), JSON.stringify(data), {
    mode: 0o600,
  });
}
let csrf;
async function post(path, body, key = randomUUID()) {
  const response = await api.post(path, {
    data: body,
    headers: { "X-CSRF-TOKEN": csrf, "Idempotency-Key": key },
  });
  if (response.status() >= 400) {
    const error = await response.json();
    throw new Error(
      `Operation rejected: ${response.status()} ${error.error?.code}`,
    );
  }
  return response.json();
}
async function wait(path, states, timeout = 660000) {
  const until = Date.now() + timeout;
  let previous = "";
  while (Date.now() < until) {
    const response = await api.get(path);
    assert.equal(response.status(), 200);
    const value = await response.json();
    if (value.status !== previous) {
      previous = value.status;
      console.log(path.split("/").slice(-2).join("/") + ": " + previous);
    }
    if (states.includes(value.status)) return value;
    if (["FAILED", "INTERRUPTED", "CANCELLED"].includes(value.status))
      throw new Error(value.errorCode ?? value.status);
    await delay(1000);
  }
  throw new Error("Verification deadline exceeded");
}
try {
  csrf = (await (await api.get("/api/csrf")).json()).token;
  await post("/api/session", {});
  csrf = (await (await api.get("/api/csrf")).json()).token;
  data ??= { projects: [] };
  for (let i = 0; i < (browserOnly ? 1 : 2); i++) {
    let p = data.projects[i];
    if (!p) {
      p = await post("/api/test-fixtures", { fixture: "todo-history" });
      data.projects.push(p);
      await persist();
    }
    const project = p.project.id;
    if (!p.buildId) {
      p.buildId = (
        await post(`/api/projects/${project}/builds`, {
          versionId: p.version.id,
        })
      ).buildId;
      await persist();
    }
    await wait(`/api/projects/${project}/builds/${p.buildId}`, ["SUCCEEDED"]);
    let previous = p.instanceId
      ? await (
          await api.get(`/api/projects/${project}/instances/${p.instanceId}`)
        ).json()
      : null;
    if (
      !previous ||
      ["STOPPED", "FAILED", "INTERRUPTED"].includes(previous.status)
    ) {
      p.instanceId = (
        await post(`/api/projects/${project}/instances`, { buildId: p.buildId })
      ).instanceId;
      await persist();
    }
    const path = `/api/projects/${project}/instances/${p.instanceId}`;
    const running = await wait(path, ["RUNNING"], 105000);
    const app = await api.get(path + "/app");
    assert.equal(app.status(), 200);
    assert.equal((await app.json()).loadedRuntimeId, running.loadedRuntimeId);
    const state = await api.get(path + "/state");
    assert.equal(state.status(), 200);
    const before = await state.json();
    const value = {
      todos: [{ id: randomUUID(), title: "独立实例 " + i, done: false }],
    };
    const body = {
      loadedRuntimeId: running.loadedRuntimeId,
      stateSchemaVersion: 1,
      expectedRevision: before.revision,
      value,
    };
    const headers = { "X-CSRF-TOKEN": csrf, "Idempotency-Key": randomUUID() };
    const saved = await api.put(path + "/state", { data: body, headers });
    assert.equal(saved.status(), 200);
    assert.deepEqual(
      (await api.put(path + "/state", { data: body, headers })).status(),
      200,
    );
    p.saved = await saved.json();
    await persist();
    const document = await api.get(path + "/document");
    assert.equal(document.status(), 200);
    assert.equal(document.headers()["x-frame-options"], "SAMEORIGIN");
    assert.match(
      document.headers()["content-security-policy"],
      /sandbox allow-scripts/,
    );
    if (browserOnly) continue;
    await post(path + "/stops", {});
    await wait(path, ["STOPPED"], 30000);
    assert.equal((await api.get(path + "/state")).status(), 503);
    p.instanceId = (
      await post(`/api/projects/${project}/instances`, { buildId: p.buildId })
    ).instanceId;
    await persist();
    const restarted = `/api/projects/${project}/instances/${p.instanceId}`;
    await wait(restarted, ["RUNNING"], 105000);
    assert.deepEqual(
      await (await api.get(restarted + "/state")).json(),
      p.saved,
    );
  }
  if (!browserOnly) {
    assert.notDeepEqual(
      data.projects[0].saved.value,
      data.projects[1].saved.value,
    );
    console.log(
      "Runtime verification passed: two real builds, distinct databases, save/replay/stop/restart persistence.",
    );
  }
  await persist();
  await verifyInstanceBrowser({
    root,
    work,
    env,
    storageState: await api.storageState(),
    project: data.projects[0],
  });
} finally {
  for (const p of data?.projects ?? []) {
    try {
      const base = `/api/projects/${p.project.id}/instances`;
      const result = await api.get(base);
      if (result.status() !== 200) continue;
      const active = (await result.json()).items.find((item) =>
        ["QUEUED", "STARTING", "RUNNING", "STOPPING"].includes(item.status),
      );
      if (active) {
        await post(base + "/" + active.id + "/stops", {});
        await wait(base + "/" + active.id, ["STOPPED"], 30000);
        p.instanceId = active.id;
      }
    } catch {
      console.log("Fixture shutdown unresolved; execution fences retained.");
    }
  }
  await persist();
  await api.dispose();
  await platform.stop();
}

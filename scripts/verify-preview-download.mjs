// Test-only: accepts the private work directory produced by verify-app-runtime.mjs.
// Runs only the reviewed todo fixture, never arbitrary user/model source.
import assert from "node:assert/strict";
import { readFile, realpath, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { completedBuild } from "./verify-workspace-build.mjs";
import { verifyStandalone } from "./verify-standalone.mjs";
import { verifyRuntimePair } from "./workspace-runtime-pair.mjs";
import { runCli } from "../templates/standalone-v1/scripts/process.mjs";

if (process.argv.length !== 3)
  throw new Error("Expected the private runtime verification directory.");
const root = fileURLToPath(new URL("..", import.meta.url));
const work = await realpath(process.argv[2]);
await runCli(async (signal) => {
  const build = JSON.parse(
    await readFile(join(work, "preview-build.json"), "utf8"),
  );
  const fixture = JSON.parse(
    await readFile(join(work, "projects.json"), "utf8"),
  ).projects[0];
  completedBuild(build, fixture.project.id, fixture.version.id);
  assert.equal(build.id, fixture.buildId);
  const configDir = join(root, ".local/standalone");
  const rebuild = await verifyStandalone(
    {
      configDir,
      outputParent: work,
      downloadedZip: join(work, "runtime-engineering.zip"),
    },
    signal,
  );
  assert.equal(rebuild.archiveSha256, build.archiveSha256);
  assert.equal(rebuild.sourceVersionId, build.versionId);
  assert.equal(rebuild.loadedRuntimeId, build.loadedRuntimeId);
  await verifyRuntimePair(
    {
      configDir,
      output: work,
      loadedRuntimeId: build.loadedRuntimeId,
      firstJar: join(work, "builds", build.id, "result/app.jar"),
      firstHash: build.jarSha256,
      secondJar: join(rebuild.directory, "backend/target/app.jar"),
      secondHash: rebuild.jarSha256,
    },
    signal,
  );
  await writeFile(
    join(work, "preview-parity.json"),
    JSON.stringify(
      {
        buildId: build.id,
        verifiedAt: new Date().toISOString(),
        rebuild,
        checks: [
          "browser-downloaded-archive-hash",
          "new-directory-full-build",
          "runtime-content-identity",
          "both-jars-add-toggle-save-reload-restart",
        ],
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  console.log(
    "Preview/download parity passed: frozen source, fresh full build, matching runtime content and persistent behavior.",
  );
}, "Preview/download verification failed. Evidence retained; only owned processes stopped.");

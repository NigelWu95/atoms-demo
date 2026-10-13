import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { startOwned } from "./standalone-process.mjs";

/** Uses an isolated Chrome profile and only the caller-created fixture session. */
export async function verifyInstanceBrowser({
  root,
  work,
  env,
  storageState,
  project,
}) {
  const { chromium } =
    await import("../frontend/node_modules/playwright/index.mjs");
  const { expect } =
    await import("../frontend/node_modules/@playwright/test/index.mjs");
  const { default: AxeBuilder } =
    await import("../frontend/node_modules/@axe-core/playwright/dist/index.mjs");
  const frontend = await startOwned(
    process.execPath,
    [
      join(root, "frontend/node_modules/vite/bin/vite.js"),
      "--host",
      "127.0.0.1",
      "--mode",
      "fixtures",
    ],
    { cwd: join(root, "frontend"), env, baseURL: "http://127.0.0.1:5174" },
  );
  let browser, page;
  try {
    browser = await chromium.launch({ channel: "chrome", headless: true });
    const context = await browser.newContext({
      storageState,
      baseURL: "http://127.0.0.1:5174",
    });
    page = await context.newPage();
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.name));
    page.on("console", (message) => {
      if (["warning", "error"].includes(message.type()))
        errors.push(message.text().slice(0, 160));
    });
    const base = `/api/projects/${project.project.id}/instances`;
    await page.goto(`http://127.0.0.1:5174/projects/${project.project.id}`);
    const panel = page.getByRole("region", { name: "本机应用进程" });
    const previewDownload = page.getByRole("region", { name: "预览工程下载" });
    const downloadButton = previewDownload.getByRole("button", {
      name: "下载预览工程 ZIP",
      exact: true,
    });
    await expect(panel).toContainText("运行中", { timeout: 15000 });
    const frame = page.frameLocator('iframe[title="本机应用预览"]');
    await expect(frame.getByLabel("新待办")).toBeVisible({ timeout: 15000 });
    await frame.getByLabel("新待办").fill("真实实例浏览器保存");
    await frame.getByRole("button", { name: "添加待办" }).click();
    await expect(
      page.getByText("本机应用 · 已保存到数据库", { exact: true }),
    ).toBeVisible();
    await frame
      .getByRole("checkbox", { name: "真实实例浏览器保存", exact: true })
      .check();
    await expect(
      page.getByText("本机应用 · 已保存到数据库", { exact: true }),
    ).toBeVisible();
    await page.reload();
    await expect(
      frame.getByText("真实实例浏览器保存", { exact: true }),
    ).toBeVisible();
    await expect(
      frame.getByRole("checkbox", { name: "真实实例浏览器保存", exact: true }),
    ).toBeChecked();
    const build = await (
      await page.request.get(
        `/api/projects/${project.project.id}/builds/${project.buildId}`,
      )
    ).json();
    const downloadEvent = page.waitForEvent("download");
    await expect(previewDownload).toContainText(project.buildId.slice(0, 8));
    await downloadButton.focus();
    await page.keyboard.press("Enter");
    const download = await downloadEvent;
    await download.saveAs(join(work, "runtime-engineering.zip"));
    assert.equal(
      createHash("sha256")
        .update(await readFile(join(work, "runtime-engineering.zip")))
        .digest("hex"),
      build.archiveSha256,
    );
    await writeFile(join(work, "preview-build.json"), JSON.stringify(build), {
      mode: 0o600,
    });
    const modes = page.getByRole("group", { name: "预览运行环境" });
    await modes
      .getByRole("button", { name: "平台共享预览", exact: true })
      .click();
    await expect(downloadButton).toBeDisabled();
    await modes
      .getByRole("button", { name: "本机运行预览", exact: true })
      .click();
    await expect(downloadButton).toBeEnabled();
    for (const width of [1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      if (width < 768)
        await page
          .getByRole("group", { name: "工作台区域" })
          .getByRole("button", { name: "预览", exact: true })
          .click();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      assert.deepEqual(
        (
          await new AxeBuilder({ page })
            .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
            .analyze()
        ).violations,
        [],
      );
      // Bring the outer frame onscreen before waiting for animation frames in
      // the offscreen cross-origin child (Chrome can throttle those frames).
      await page
        .locator('iframe[title="本机应用预览"]')
        .scrollIntoViewIfNeeded();
      await expect(
        frame.getByText("真实实例浏览器保存", { exact: true }),
      ).toBeVisible();
      await frame.getByLabel("新待办").fill("窄屏输入检查");
      await expect(frame.getByLabel("新待办")).toHaveValue("窄屏输入检查");
      await frame.getByLabel("新待办").fill("");
      await page.screenshot({ path: join(work, `runtime-app-${width}.png`) });
      await page.screenshot({
        path: join(work, `runtime-${width}.png`),
        fullPage: true,
      });
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await panel.getByRole("button", { name: "停止应用", exact: true }).click();
    await expect(panel).toContainText("已停止", { timeout: 30000 });
    await expect(page.locator("iframe")).toHaveCount(0);
    await expect(downloadButton).toBeDisabled();
    await expect(
      page.getByText("当前版本没有运行中的本机应用。", { exact: false }),
    ).toBeVisible();
    await panel.getByRole("button", { name: "启动应用", exact: true }).focus();
    await page.keyboard.press("Enter");
    await expect(panel).toContainText("运行中", { timeout: 105000 });
    await expect(
      frame.getByText("真实实例浏览器保存", { exact: true }),
    ).toBeVisible({ timeout: 15000 });
    await expect(downloadButton).toBeEnabled();
    await expect(
      frame.getByRole("checkbox", { name: "真实实例浏览器保存", exact: true }),
    ).toBeChecked();
    const stranger = await browser.newContext();
    try {
      const csrf = await (
        await stranger.request.get("http://127.0.0.1:5174/api/csrf")
      ).json();
      await stranger.request.post("http://127.0.0.1:5174/api/session", {
        data: {},
        headers: { "X-CSRF-TOKEN": csrf.token },
      });
      assert.equal(
        (await stranger.request.get("http://127.0.0.1:5174" + base)).status(),
        404,
      );
    } finally {
      await stranger.close();
    }
    assert.deepEqual(errors, []);
    console.log(
      "Browser verification passed: real process preview, add/toggle/save/reload/restart, preview-bound frozen ZIP, disabled shared/stopped downloads, 320/390/768/1024/1440px, accessibility and owner isolation.",
    );
  } catch (error) {
    if (page) {
      await page.screenshot({
        path: join(work, "runtime-failure.png"),
        fullPage: true,
      });
      console.log(
        "Browser alerts: " +
          JSON.stringify(await page.getByRole("alert").allTextContents()),
      );
    }
    throw error;
  } finally {
    await browser?.close();
    await frontend.stop();
  }
}

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
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
    const panel = page.getByRole("region", { name: "独立应用实例" });
    await expect(panel).toContainText("运行中", { timeout: 15000 });
    const frame = page.frameLocator('iframe[title="独立实例应用预览"]');
    await expect(frame.getByLabel("新待办")).toBeVisible({ timeout: 15000 });
    await frame.getByLabel("新待办").fill("真实实例浏览器保存");
    await frame.getByRole("button", { name: "添加待办" }).click();
    await expect(
      page.getByText("独立实例 · 已保存到数据库", { exact: true }),
    ).toBeVisible();
    await page.reload();
    await expect(
      frame.getByText("真实实例浏览器保存", { exact: true }),
    ).toBeVisible();
    const build = await (
      await page.request.get(
        `/api/projects/${project.project.id}/builds/${project.buildId}`,
      )
    ).json();
    const downloadEvent = page.waitForEvent("download");
    await panel
      .getByRole("button", {
        name: `下载构建 ${project.buildId.slice(0, 8)} 的工程 ZIP`,
      })
      .click();
    const download = await downloadEvent;
    await download.saveAs(join(work, "runtime-engineering.zip"));
    assert.equal(
      createHash("sha256")
        .update(await readFile(join(work, "runtime-engineering.zip")))
        .digest("hex"),
      build.archiveSha256,
    );
    for (const width of [1440, 390, 320]) {
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
        .locator('iframe[title="独立实例应用预览"]')
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
    await panel
      .getByRole("button", { name: "停止独立实例", exact: true })
      .click();
    await expect(panel).toContainText("已停止", { timeout: 30000 });
    await expect(page.locator("iframe")).toHaveCount(0);
    await expect(
      page.getByText("当前版本没有运行中的独立实例。", { exact: false }),
    ).toBeVisible();
    await panel
      .getByRole("button", { name: "启动独立实例", exact: true })
      .focus();
    await page.keyboard.press("Enter");
    await expect(panel).toContainText("运行中", { timeout: 105000 });
    await expect(
      frame.getByText("真实实例浏览器保存", { exact: true }),
    ).toBeVisible({ timeout: 15000 });
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
      "Browser verification passed: real process preview, state save/reload/restart, frozen ZIP, 320/390/1440px, accessibility and owner isolation.",
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

import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import path from "node:path";
import puppeteer from "puppeteer-core";
import { chromePath } from "../scripts/chrome.mjs";

const here = path.dirname(new URL(import.meta.url).pathname);
const shots = path.join(here, "screenshots");
mkdirSync(shots, { recursive: true });
const port = 5196;
const executablePath = await chromePath();
const preview = spawn(
  "npx",
  ["vite", "preview", "--port", String(port), "--strictPort"],
  { cwd: here, stdio: "ignore" },
);

const results = {};
const failures = [];
const expect = (name, ok, actual) => {
  results[name] = actual;
  if (!ok) failures.push(name);
};

let browser;
try {
  await new Promise((r) => setTimeout(r, 2000));
  browser = await puppeteer.launch({ executablePath, headless: true });
  const page = await browser.newPage();
  await page.setViewport({ width: 1400, height: 900 });
  const logs = [];
  const requests = [];
  page.on("console", (m) => logs.push(`${m.type()}: ${m.text()}`));
  page.on("pageerror", (e) => logs.push(`pageerror: ${e.message}`));
  page.on("requestfinished", (r) => requests.push(`finished ${r.url()}`));
  page.on("requestfailed", (r) =>
    requests.push(`failed ${r.url()} ${r.failure()?.errorText}`),
  );
  await page.goto(`http://localhost:${port}/`, { waitUntil: "networkidle0" });

  const viewFrame = () =>
    page.frames().find((f) => f.parentFrame() === page.mainFrame());
  const initializedCount = () =>
    page.evaluate(() =>
      Number(
        document
          .querySelector("iframe.ov-frame")
          ?.getAttribute("data-view-initialized") ?? 0,
      ),
    );
  const waitInitialized = () =>
    page.waitForFunction(
      () =>
        Number(
          document
            .querySelector("iframe.ov-frame")
            ?.getAttribute("data-view-initialized"),
        ) > 0,
      { timeout: 10000 },
    );
  const inView = (fn) => viewFrame().evaluate(fn);
  const errors = () =>
    page.evaluate(() =>
      [...document.querySelectorAll('[data-testid="errors"] li')].map(
        (li) => li.textContent,
      ),
    );
  const render = async (html) => {
    await page.evaluate((text) => {
      const el = document.querySelector('[data-testid="html-input"]');
      const setter = Object.getOwnPropertyDescriptor(
        HTMLTextAreaElement.prototype,
        "value",
      ).set;
      setter.call(el, text);
      el.dispatchEvent(new Event("input", { bubbles: true }));
    }, html);
    await page.click('[data-testid="render-button"]');
    await new Promise((r) => setTimeout(r, 300));
  };
  const settle = () => new Promise((r) => setTimeout(r, 500));

  await waitInitialized();
  await settle();
  const rows = await inView(() =>
    [...document.querySelectorAll(".row .label")].map((l) => l.textContent),
  );
  expect("data reaches the view", rows.join() === "gpt-4o,claude", rows);
  const light = await inView(() => ({
    theme: document.documentElement.getAttribute("data-theme"),
    fg: document.documentElement.style.getPropertyValue("--ov-fg"),
  }));
  expect(
    "light theme and tokens",
    light.theme === "light" && light.fg === "hsl(224 17% 26%)",
    light,
  );
  const height = await page.evaluate(
    () =>
      document.querySelector("iframe.ov-frame").getBoundingClientRect().height,
  );
  expect(
    "frame follows the content height",
    height > 40 && height < 200,
    height,
  );
  await page.screenshot({
    path: path.join(shots, "light.png"),
    fullPage: true,
  });

  await page.click('[data-testid="theme-button"]');
  await page.waitForFunction(
    () =>
      document.querySelector(".ov-view")?.getAttribute("data-theme") === "dark",
  );
  await settle();
  const dark = await inView(() => ({
    theme: document.documentElement.getAttribute("data-theme"),
    fg: document.documentElement.style.getPropertyValue("--ov-fg"),
  }));
  expect(
    "theme change reaches the view without a reload",
    dark.theme === "dark" && dark.fg === "hsl(214 32% 91%)",
    dark,
  );
  await page.screenshot({ path: path.join(shots, "dark.png"), fullPage: true });

  await render(
    '<pre id="out">pending</pre><script>' +
      "var out = [];" +
      'try { parent.document.title; out.push("parent:open"); } catch (e) { out.push("parent:closed"); }' +
      'try { document.cookie; out.push("cookie:open"); } catch (e) { out.push("cookie:closed"); }' +
      'out.push("webrtc:" + typeof RTCPeerConnection);' +
      'try { eval("1"); out.push("eval:open"); } catch (e) { out.push("eval:closed"); }' +
      'var img = new Image(); img.src = "http://localhost:' +
      port +
      '/probe-image.png";' +
      'fetch("http://localhost:' +
      port +
      '/probe-fetch").then(function () { out.push("fetch:open"); }, function () { out.push("fetch:closed"); }).then(function () { document.getElementById("out").textContent = out.join(" "); });' +
      "</script>",
  );
  await waitInitialized();
  await viewFrame().waitForFunction(
    () => document.getElementById("out")?.textContent !== "pending",
    { timeout: 10000 },
  );
  const probes = await inView(() => document.getElementById("out").textContent);
  expect(
    "forbidden paths stay closed",
    probes ===
      "parent:closed cookie:closed webrtc:undefined eval:closed fetch:closed",
    probes,
  );
  expect(
    "no request leaves the view",
    !requests.some((r) => r.startsWith("finished") && r.includes("/probe-")),
    requests.filter((r) => r.includes("/probe-")),
  );

  await render(
    '<script>onViewData(function (d) { if (d) location.href = "http://127.0.0.1:' +
      port +
      '/probe-navigation?d=" + encodeURIComponent(JSON.stringify(d)); })</script>',
  );
  const navigation = await page
    .waitForFunction(
      () =>
        document.querySelector(".ov-view .ov-error")?.textContent ===
        "The view tried to leave its sandbox and was stopped.",
      { timeout: 10000 },
    )
    .then(() => "stopped")
    .catch(() => "not stopped");
  expect("self-navigation is stopped", navigation === "stopped", navigation);
  expect(
    "self-navigation sends nothing out",
    !requests.some((r) => r.includes("/probe-navigation") && !/csp/i.test(r)),
    requests.filter((r) => r.includes("/probe-navigation")),
  );

  await render(
    '<script>onViewData(function (d) { throw new Error("boom " + d.cost.length); })</script>',
  );
  await waitInitialized();
  await page
    .waitForFunction(
      () =>
        [...document.querySelectorAll('[data-testid="errors"] li')].length > 0,
      { timeout: 5000 },
    )
    .catch(() => {});
  const scriptErrors = await errors();
  expect(
    "script errors reach the host",
    scriptErrors.some((e) => e.includes("boom 2")),
    scriptErrors,
  );

  await render(
    '<p id="second">pending</p><script>onViewData(function () { throw new Error("first callback"); }); onViewData(function (d) { document.getElementById("second").textContent = "got " + d.cost.length; });</script>',
  );
  await waitInitialized();
  await viewFrame()
    .waitForFunction(
      () => document.getElementById("second")?.textContent !== "pending",
      { timeout: 5000 },
    )
    .catch(() => {});
  const second = await inView(
    () => document.getElementById("second").textContent,
  );
  const callbackErrors = await errors();
  expect(
    "a failing data callback does not stop the next one",
    second === "got 2" &&
      callbackErrors.some((e) => e.includes("first callback")),
    { second, callbackErrors },
  );

  await render(
    '<div id="box" style="height:40px"></div><script>onViewData(function () { setTimeout(function () { document.getElementById("box").style.height = "41px"; }, 1000); setTimeout(function () { document.getElementById("box").style.height = "400px"; }, 1100); })</script>',
  );
  await waitInitialized();
  await page.evaluate(
    () =>
      new Promise((resolve) => {
        const poll = () => {
          const frame = document.querySelector("iframe.ov-frame");
          if (frame?.getBoundingClientRect().height === 41) {
            setTimeout(() => {
              document.querySelector('[data-testid="theme-button"]').click();
              resolve();
            }, 150);
          } else requestAnimationFrame(poll);
        };
        poll();
      }),
  );
  await settle();
  const settledHeight = await page.evaluate(
    () =>
      document.querySelector("iframe.ov-frame").getBoundingClientRect().height,
  );
  expect(
    "frame keeps the last height across a theme change",
    settledHeight >= 400,
    settledHeight,
  );

  await render(
    '<script>for (var i = 0; i < 200; i++) setTimeout(function (n) { throw new Error("flood " + (n % 50)); }, 0, i);</script>',
  );
  await waitInitialized();
  await new Promise((r) => setTimeout(r, 1000));
  const flood = await errors();
  expect(
    "repeated script errors are reported once each, up to a limit",
    flood.length === 20 && new Set(flood).size === 20,
    flood.length,
  );

  await render(
    '<div id="name"></div><script>onViewData(function (d) { document.getElementById("name").textContent = d.cost[0].model; })</script>' +
      "<p>x</p>".repeat(100000),
  );
  await page.waitForFunction(
    () =>
      Number(
        document
          .querySelector("iframe.ov-frame")
          ?.getAttribute("data-view-loads"),
      ) > 0,
    { timeout: 20000 },
  );
  await waitInitialized();
  await settle();
  const large = {
    initialized: await initializedCount(),
    name: await inView(() => document.getElementById("name")?.textContent),
  };
  expect(
    "a large view initializes",
    large.initialized > 0 && large.name === "gpt-4o",
    large,
  );

  const unknown = {
    view: { type: "html", engine_version: 99, html: "<p>x</p>", inputs: {} },
    data: {},
  };
  await page.goto(
    `http://localhost:${port}/#load=${encodeURIComponent(JSON.stringify(unknown))}`,
    { waitUntil: "networkidle0" },
  );
  await page.reload({ waitUntil: "networkidle0" });
  const unknownError = await page.evaluate(
    () => document.querySelector(".ov-view .ov-error")?.textContent,
  );
  expect(
    "unknown engine version shows the error state",
    unknownError?.includes("version 99") === true,
    unknownError,
  );

  const chart = {
    view: {
      type: "html",
      engine_version: 1,
      libraries: ["vega"],
      inputs: { cost: { kind: "query", query_id: "q-cost" } },
      html: '<div id="chart" style="width:100%"></div><script>onViewData(function (d) { vegaEmbed("#chart", { width: "container", height: 120, data: { values: d.cost }, mark: "bar", encoding: { x: { field: "model", type: "nominal" }, y: { field: "usd", type: "quantitative" } } }); })</script>',
    },
    data: { cost: [{ model: "gpt-4o", usd: 12.4 }] },
  };
  await page.goto(
    `http://localhost:${port}/#load=${encodeURIComponent(JSON.stringify(chart))}`,
    { waitUntil: "networkidle0" },
  );
  await page.reload({ waitUntil: "networkidle0" });
  await waitInitialized();
  const drawn = await viewFrame()
    .waitForFunction(
      () => document.querySelector("#chart svg path, #chart svg rect"),
      { timeout: 10000 },
    )
    .then(
      () => true,
      () => false,
    );
  expect("a declared library renders a chart", drawn, await errors());
  const vegaRequests = () =>
    requests.filter((r) => r.startsWith("finished") && r.includes("vega"))
      .length;
  const vegaAfterChart = vegaRequests();

  await render(
    '<p id="themes"></p><script>var seen = []; onViewTheme(function (theme) { seen.push(theme + ":" + getComputedStyle(document.documentElement).getPropertyValue("--ov-fg").trim()); document.getElementById("themes").textContent = seen.join(" | "); });</script>',
  );
  await waitInitialized();
  const themeBefore = await page.evaluate(() =>
    document.querySelector(".ov-view").getAttribute("data-theme"),
  );
  await page.click('[data-testid="theme-button"]');
  await settle();
  const themesSeen = await inView(() =>
    document.getElementById("themes").textContent.split(" | "),
  );
  expect(
    "theme callback runs with the tokens set, on initialize and on a switch",
    themesSeen.length === 2 &&
      themesSeen[0].startsWith(themeBefore + ":hsl") &&
      !themesSeen[1].startsWith(themeBefore) &&
      /:hsl/.test(themesSeen[1]) &&
      themesSeen[0].split(":")[1] !== themesSeen[1].split(":")[1],
    themesSeen,
  );

  await render(
    '<p id="late">pending</p><script>setTimeout(function () { onViewData(function () { throw new Error("late callback"); }); onViewData(function (d) { document.getElementById("late").textContent = "got " + d.cost.length; }); }, 500);</script>',
  );
  await waitInitialized();
  const late = await viewFrame()
    .waitForFunction(
      () => document.getElementById("late")?.textContent !== "pending",
      { timeout: 5000 },
    )
    .then(() => inView(() => document.getElementById("late").textContent))
    .catch(() => "pending");
  expect(
    "a failing callback registered after the data arrived does not stop the next one",
    late === "got 1" &&
      (await errors()).some((e) => e.includes("late callback")),
    { late, errors: await errors() },
  );
  expect(
    "a library is fetched once across re-renders",
    vegaRequests() === vegaAfterChart,
    vegaRequests() - vegaAfterChart,
  );

  await page.goto(`http://localhost:${port}/#frame`, {
    waitUntil: "networkidle0",
  });
  await page.reload({ waitUntil: "networkidle0" });
  await waitInitialized();
  const setFrame = (props) => page.evaluate((p) => window.setFrame(p), props);
  const frameText = async (id) => {
    await page.waitForSelector("iframe.ov-frame", { timeout: 5000 });
    await new Promise((r) => setTimeout(r, 300));
    if (!viewFrame()) return "no frame";
    return viewFrame()
      .waitForFunction(
        (el) => document.getElementById(el)?.textContent?.startsWith("got"),
        { timeout: 5000 },
        id,
      )
      .then(() => inView(() => document.querySelector("p").textContent))
      .catch(() => "not rendered");
  };
  const stoppedShown = () =>
    page
      .waitForFunction(
        () =>
          document.querySelector(".ov-view .ov-error")?.textContent ===
          "The view tried to leave its sandbox and was stopped.",
        { timeout: 5000 },
      )
      .then(
        () => true,
        () => false,
      );
  const dataView =
    '<p id="v">pending</p><script>onViewData(function (d) { document.getElementById("v").textContent = "got " + d.cost[0].model; })</script>';

  await page.evaluate(() => {
    document.querySelector("iframe.ov-frame").dataset.mark = "first";
  });
  await setFrame({ html: dataView, engineVersion: 1 });
  const inPlace = {
    text: await frameText("v"),
    sameElement: await page.evaluate(
      () => document.querySelector("iframe.ov-frame")?.dataset.mark,
    ),
    errors: await errors(),
  };
  expect(
    "an html change re-renders the same frame",
    inPlace.text === "got gpt-4o" &&
      inPlace.sameElement === "first" &&
      inPlace.errors.length === 0,
    inPlace,
  );

  await setFrame({ html: dataView, engineVersion: 99 });
  await page.waitForSelector(".ov-view .ov-error");
  await setFrame({ html: dataView, engineVersion: 1 });
  const remount = { text: await frameText("v"), errors: await errors() };
  expect(
    "the same html renders again after an unsupported version",
    remount.text === "got gpt-4o" && remount.errors.length === 0,
    remount,
  );

  await setFrame({
    html: '<script>onViewData(function () { location.href = "about:blank"; })</script>',
    engineVersion: 1,
  });
  const stoppedFirst = await stoppedShown();
  await setFrame({ html: dataView, engineVersion: 1 });
  const recovered = await frameText("v");
  expect(
    "a stopped frame recovers with new html",
    stoppedFirst && recovered === "got gpt-4o",
    { stoppedFirst, recovered },
  );

  const unexpected = logs.filter(
    (l) =>
      /^(error|pageerror)/.test(l) &&
      !/favicon|probe-|Content Security Policy|TrustedHTML|TrustedScript|EvalError|unsafe-eval|boom|flood|first callback|late callback|Failed to load resource|Failed to fetch/.test(
        l,
      ),
  );
  expect("no unexpected console errors", unexpected.length === 0, unexpected);
} catch (error) {
  failures.push(`threw: ${error instanceof Error ? error.message : error}`);
} finally {
  await browser?.close();
  preview.kill();
}
console.log(JSON.stringify({ results, failures }, null, 2));
process.exit(failures.length ? 1 : 0);

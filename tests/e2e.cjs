// Browser test of the demo page: node tests/e2e.cjs http://localhost:PORT/ [screenshot-dir]
const { chromium } = require("playwright");
const base = process.argv[2] || "http://localhost:8000/";
const shots = process.argv[3];
const check = (ok, what) => { if (!ok) { console.error("FAIL: " + what); process.exit(1); } console.log("ok: " + what); };

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 400, height: 820 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  await page.goto(base);
  await page.waitForSelector(".shell");
  check(await page.isVisible("text=Add your DeepSeek API key"), "asks for a key first");
  if (shots) await page.screenshot({ path: shots + "/1-no-key.png" });

  await page.click("button:has-text('Open settings')");
  const key = page.locator(".key-row input");
  check(await key.getAttribute("type") === "password", "key field is masked");
  await key.fill("sk-test-1234567890abcd");
  await page.click("button:has-text('Show')");
  await page.waitForSelector(".key-row input[type=text]", { timeout: 2000 }).catch(() => {});
  check(await key.getAttribute("type") === "text", "Show reveals the key");
  if (shots) await page.screenshot({ path: shots + "/2-settings.png" });
  await page.click("button:has-text('Save key')");
  await page.waitForSelector("text=Full health check");
  check(await page.isVisible("text=Ready"), "status is Ready after saving a key");
  if (shots) await page.screenshot({ path: shots + "/3-chat-empty.png" });

  await page.click("button:has-text('Battery drains fast')");
  await page.waitForSelector(".msg.user");
  check(await page.isVisible("text=Working"), "status is Working during a turn");
  await page.waitForSelector("text=Ready", { timeout: 10000 });
  check(await page.locator(".msg.tool").count() === 1, "tool row shown");
  check((await page.locator(".msg.assistant").textContent()).includes("browser demo"), "assistant reply shown");
  if (shots) await page.screenshot({ path: shots + "/4-chat.png" });

  await page.fill(".composer input", "what about storage?");
  await page.press(".composer input", "Enter");
  await page.waitForFunction(() => document.querySelectorAll(".msg.user").length === 2);
  check(true, "Enter sends a typed prompt");
  await page.waitForSelector("text=Ready", { timeout: 10000 });

  await page.click("button.tab:has-text('Settings')");
  check(await page.waitForSelector(".saved code:has-text('sk-…abcd')", { timeout: 2000 }).then(() => true, () => false), "saved key shows only a hint");
  await page.click("button:has-text('Remove key')");
  await page.click("button.tab:has-text('Chat')");
  check(await page.waitForSelector("text=Add your DeepSeek API key", { timeout: 2000 }).then(() => true, () => false), "removing the key asks again");
  check(errors.length === 0, "no page errors " + errors.join(" | "));
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });

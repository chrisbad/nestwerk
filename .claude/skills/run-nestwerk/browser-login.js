// Logs in as admin and opens the week grid. Run with the Playwright MCP tool
// browser_run_code_unsafe { filename: ".claude/skills/run-nestwerk/browser-login.js" }
// after `driver.ps1 start`. Change the viewport for tablet (820) or phone (390) checks.
async (page) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('http://localhost:8099/login');
  if (page.url().endsWith('/login')) {
    await page.fill('input[name=username]', 'admin');
    await page.fill('input[name=password]', 'test-admin-pw');
    await page.click('button[type=submit]');
    await page.waitForLoadState('networkidle');
  }
  await page.goto('http://localhost:8099/calendar/week');
  await page.waitForSelector('.tgrid');
  // Pages fade in; a screenshot taken right away is dimmed and empty.
  await page.waitForTimeout(1000);
  return `${page.url()} | ${await page.title()}`;
}

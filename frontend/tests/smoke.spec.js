import { test, expect } from '@playwright/test'
import { readFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

function adminPassword() {
  if (process.env.ADMIN_PASSWORD) return process.env.ADMIN_PASSWORD
  const lines = readFileSync(resolve('../backend/.env.local'), 'utf8').split(/\r?\n/)
  const line = lines.find(value => /^ADMIN_PASSWORD\s*=/.test(value))
  if (!line) throw new Error('Configure ADMIN_PASSWORD locally before browser tests')
  // Java properties escapes, without writing the value to the console or a report.
  return line.slice(line.indexOf('=') + 1).replace(/^\s+/, '').replace(/\\u([\da-f]{4})|\\(.)/gi, (_, unicode, char) => unicode ? String.fromCharCode(parseInt(unicode, 16)) : ({ n: '\n', r: '\r', t: '\t', f: '\f' }[char] || char))
}
async function login(page, account, password) {
  await page.goto('/')
  await page.getByRole('textbox', { name: '学号 / 管理员账号' }).fill(account)
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(password)
  await page.getByRole('button', { name: '登录静习' }).click()
  await expect(page.locator('.sidebar')).toBeVisible()
}
async function request(context, path, body) {
  const token = await context.get('/api/user/csrf').then(r => r.json())
  const response = await context.post('/api' + path, { headers: { 'X-CSRF-Token': token.data.csrfToken }, data: body })
  expect(response.ok(), 'API operation should succeed').toBeTruthy()
  return (await response.json()).data
}

test('student registers, books, cancels, receives recommendations and uses mobile layout', async ({ page }) => {
  const account = 'e2e_' + randomUUID().slice(0, 12)
  const password = randomUUID()
  const errors = []; page.on('pageerror', error => errors.push(error.message))
  await page.goto('/')
  await page.getByRole('button', { name: '注册学生账号' }).click()
  await page.getByRole('textbox', { name: '学号 / 管理员账号' }).fill(account)
  await page.getByRole('textbox', { name: '姓名', exact: true }).fill('浏览器测试同学')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(password)
  await page.getByRole('button', { name: '注册账号', exact: false }).click()
  await expect(page.getByRole('heading', { name: '开始你的学习计划' })).toBeVisible()
  await login(page, account, password)
  await page.getByRole('button', { name: '查看座位' }).first().click()
  await expect(page.locator('.seat')).toHaveCount(24)
  await page.locator('.seat:not(:disabled)').first().click()
  mkdirSync(resolve('../.cache'), { recursive: true })
  await page.screenshot({ path: resolve('../.cache/student-desktop.png'), fullPage: true })
  await page.getByRole('button', { name: '确认预约 →', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认预约', exact: true }).click()
  await expect(page.getByText('预约成功，可在“我的预约”查看')).toBeVisible()
  await page.getByRole('button', { name: '我的预约' }).click()
  await expect(page.locator('.status-badge')).toHaveText('待开始')
  await page.getByRole('button', { name: '取消预约', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确定取消', exact: true }).click()
  await expect(page.locator('.status-badge')).toHaveText('已取消')
  await page.getByRole('button', { name: '为你推荐' }).click()
  await page.getByRole('button', { name: '获取推荐' }).click()
  await expect(page.locator('.recommend-card')).toHaveCount(5)
  await expect(page.locator('.recommend-card').first()).toContainText('暂无有效历史')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  await page.screenshot({ path: resolve('../.cache/student-mobile.png'), fullPage: true })
  await page.locator('.mobile-logout').click()
  await expect(page.getByRole('heading', { name: '开始你的学习计划' })).toBeVisible()
  expect(errors).toEqual([])
})

test('administrator maintains rooms and seats and views bookings and statistics', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message))
  await login(page, 'admin', adminPassword())
  const name = '浏览器验收-' + randomUUID().slice(0, 6)
  let roomId
  try {
    await page.getByRole('button', { name: '新增自习室' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('textbox', { name: '自习室名称', exact: true }).fill(name)
    await dialog.getByRole('textbox', { name: '位置', exact: true }).fill('测试空间')
    const added = page.waitForResponse(r => r.url().endsWith('/admin/room/add') && r.request().method() === 'POST')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    roomId = (await (await added).json()).data.id
    const row = page.getByRole('row').filter({ hasText: name })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '编辑', exact: true }).click()
    await dialog.getByRole('textbox', { name: '位置', exact: true }).fill('已更新位置')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(row).toContainText('已更新位置')
    await row.getByRole('button', { name: '停用', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
    await expect(row).toContainText('已停用')
    await row.getByRole('button', { name: '启用', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
    await expect(row).toContainText('已启用')
    await page.getByRole('button', { name: '座位管理' }).click()
    await page.locator('.table-toolbar .el-select').click()
    await page.getByRole('option', { name, exact: true }).click()
    await page.getByRole('button', { name: '新增座位' }).click()
    await dialog.getByRole('textbox', { name: '座位编号', exact: true }).fill('A01')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    const seat = page.getByRole('row').filter({ hasText: 'A01' })
    await expect(seat).toBeVisible()
    await seat.getByRole('button', { name: '编辑', exact: true }).click()
    await dialog.getByRole('textbox', { name: '座位编号', exact: true }).fill('A02')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByRole('row').filter({ hasText: 'A02' })).toBeVisible()
    await page.getByRole('row').filter({ hasText: 'A02' }).getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click()
    await expect(page.getByRole('row').filter({ hasText: 'A02' })).toHaveCount(0)
    await page.getByRole('button', { name: '预约记录' }).click()
    await expect(page.getByRole('heading', { name: '预约记录', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '每日统计' }).click()
    await expect(page.locator('.daily-row')).toHaveCount(7)
    await page.screenshot({ path: resolve('../.cache/admin-statistics.png'), fullPage: true })
    expect(errors).toEqual([])
  } finally { if (roomId) await request(page.request, '/admin/room/delete', { roomId }) }
})

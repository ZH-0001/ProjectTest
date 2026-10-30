import { test, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

async function student(page) {
  const account = 'asui_' + randomUUID().slice(0, 10), password = randomUUID()
  const csrf = await page.request.get('/api/user/csrf').then(r => r.json())
  await page.request.post('/api/user/register', { headers: { 'X-CSRF-Token': csrf.data.csrfToken }, data: { account, name: '助手页面验收', password } })
  await page.goto('/')
  await page.getByRole('textbox', { name: '学号 / 管理员账号' }).fill(account)
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(password)
  await page.getByRole('button', { name: '登录静习' }).click()
  await expect(page.locator('.sidebar')).toBeVisible()
}
const ok = data => ({ code: 'OK', message: '成功', data })
const state = hour => ({ startTime: { value: `2026-10-31T${hour}:00:00+08:00`, strength: 'HARD' }, endTime: { value: '2026-10-31T16:00:00+08:00', strength: 'HARD' }, hasPower: { value: true, strength: 'SOFT' } })
function card(id, hour = 14, age = 0) {
  return { cardId: id, kind: 'CANDIDATE', roomName: '知行自习室', seatNo: 'A01', seatType: 'WINDOW', hasPower: true, isQuiet: true, score: 20, reason: '本次优先有插座，暂无有效历史', startTime: `2026-10-31T${hour}:00:00+08:00`, endTime: '2026-10-31T16:00:00+08:00', generatedAt: new Date(Date.now() - age).toISOString(), expiresAt: new Date(Date.now() + 120000 - age).toISOString(), actionState: 'READY', breakdown: ['ROOM','SEAT_TYPE','POWER','QUIET'].map((dimension,i) => ({ dimension, weight: [40,30,20,10][i], score: i === 2 ? 20 : 0, source: i === 2 ? 'EXPLICIT' : 'NONE', matched: i === 2 })) }
}
test('assistant adjusts conditions, displays score sources and confirms card actions with network retry', async ({ page }) => {
  const errors=[];page.on('pageerror',e=>errors.push(e.message))
  let turn=0, bookCount=0, cancelCount=0, resetCount=0;const bookBodies=[]
  await page.route('**/api/assistant/**', async route => {
    const path = new URL(route.request().url()).pathname
    let data
    if(path.endsWith('/conversation'))data={available:true,messages:[],cards:[],conversationState:{},busy:false}
    if(path.endsWith('/chat')) {
      const payload=route.request().postDataJSON();expect(Object.keys(payload).sort()).toEqual(['message','requestId']);expect(payload.requestId).toMatch(/^[a-f0-9-]{36}$/)
      turn++
      data=turn<3?{available:true,reply:turn===1?'<img src=x onerror=alert(1)> 已查询，请确认卡片。':'开始改为15点，插座偏好保留。',conversationState:state(turn===1?14:15),cards:[card('card-'+turn,turn===1?14:15)]}:{available:true,reply:'这是你本人的待开始预约。',conversationState:state(15),cards:[{...card('my-card',15),kind:'RESERVATION',reserveId:'123',status:'PENDING'}]}
    }
    if(path.endsWith('/book')) {
      bookCount++;bookBodies.push(route.request().postDataJSON())
      if(bookCount===1)return route.abort('failed')
      data={id:'123',status:'PENDING'}
    }
    if(path.endsWith('/reset')) {resetCount++;data={available:true}}
    await route.fulfill({json:ok(data)})
  })
  await page.route('**/api/reserve/cancel',async route=>{cancelCount++;expect(route.request().postDataJSON()).toEqual({reserveId:'123'});await route.fulfill({json:ok({id:'123',status:'CANCELLED'})})})
  await student(page);await page.getByRole('button',{name:'预约助手'}).click()
  const input=page.getByRole('textbox',{name:'告诉我日期、开始与结束时间，以及你的座位偏好…'})
  await input.fill('明天14点到16点，最好有插座');await page.getByRole('button',{name:'发送消息'}).click()
  await expect(page.locator('.assistant-card')).toHaveCount(1)
  await expect(page.locator('.assistant-reply')).toContainText('<img src=x onerror=alert(1)>');await expect(page.locator('.assistant-reply img')).toHaveCount(0)
  await page.getByText('查看评分明细').click();await expect(page.locator('.breakdown-row').filter({hasText:'本次偏好'})).toContainText('20.00 / 20')
  await expect(page.locator('.breakdown-row').filter({hasText:'暂无依据'})).toHaveCount(3)
  await input.fill('开始改为15点，其余不变');await page.getByRole('button',{name:'发送消息'}).click()
  await expect(page.locator('.assistant-card')).toHaveCount(2);await expect(page.locator('.assistant-criteria')).toContainText('15:00');await expect(page.locator('.assistant-criteria')).toContainText('插座：是')
  const latest=page.locator('.assistant-card').last();await latest.getByRole('button',{name:'确认预约',exact:true}).click()
  expect(bookCount).toBe(0);await page.getByRole('dialog').getByRole('button',{name:'再看看'}).click();expect(bookCount).toBe(0)
  await latest.getByRole('button',{name:'确认预约',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'确认预约',exact:true}).click()
  await expect(latest.getByRole('button',{name:'确认上次预约结果'})).toBeVisible();await latest.getByRole('button',{name:'确认上次预约结果'}).click();await page.getByRole('dialog').getByRole('button',{name:'确认预约',exact:true}).click()
  await expect(latest).toContainText('预约已确认');expect(bookBodies).toEqual([{cardId:'card-2'},{cardId:'card-2'}])
  await input.fill('查看我的预约');await page.getByRole('button',{name:'发送消息'}).click();const own=page.locator('.assistant-card').last();await expect(own.getByRole('button',{name:'取消预约',exact:true})).toBeVisible()
  await own.getByRole('button',{name:'取消预约',exact:true}).click();expect(cancelCount).toBe(0);await page.getByRole('dialog').getByRole('button',{name:'确定取消'}).click();await expect(own).toContainText('已取消')
  await expect(page.getByRole('dialog')).not.toBeVisible();await expect(page.locator('.el-message')).toHaveCount(0)
  mkdirSync(resolve('../.cache'),{recursive:true});await page.screenshot({path:resolve('../.cache/assistant-desktop.png'),fullPage:true})
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:resolve('../.cache/assistant-mobile.png'),fullPage:true})
  await page.getByRole('button',{name:'清空会话',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'清空',exact:true}).click();await expect(page.locator('.assistant-card')).toHaveCount(0);expect(resetCount).toBe(1);expect(errors).toEqual([])
})
test('assistant preserves results on errors, disables expired cards and falls back when unconfigured', async ({page})=>{
  let enabled=true, fail=false
  const old=card('expired',14,121000)
  await page.route('**/api/assistant/**',async route=>{
    if(route.request().url().endsWith('/conversation'))return route.fulfill({json:ok({available:enabled,conversationState:state(14),busy:false,messages:[{requestId:'old',message:'查座',reply:'上次成功的结果',cardIds:['expired']}],cards:[old]})})
    fail=true;return route.fulfill({status:504,json:{code:'ASSISTANT_TIMEOUT',message:'助手处理超时，请稍后重试',data:null}})
  })
  await student(page);await page.getByRole('button',{name:'预约助手'}).click();await expect(page.locator('.assistant-card').getByRole('button',{name:'确认预约',exact:true})).toBeDisabled()
  await page.getByRole('textbox',{name:'告诉我日期、开始与结束时间，以及你的座位偏好…'}).fill('重新查询');await page.getByRole('button',{name:'发送消息'}).click()
  await expect(page.getByText('助手处理超时，请稍后重试', {exact:true})).toBeVisible();await expect(page.locator('.assistant-reply')).toContainText('上次成功');expect(fail).toBeTruthy()
  enabled=false;await page.getByRole('button',{name:'发现自习室'}).click();await page.getByRole('button',{name:'预约助手'}).click();await expect(page.getByText('预约助手尚未配置或已关闭',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'发送消息'})).toBeDisabled()
})

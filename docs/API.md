# 接口说明

开发页面使用 `/api` 前缀，Vite代理后后端实际接口不含该前缀。除注册、登录、退出、CSRF令牌接口外均需Session Cookie；学生与管理员端接口分别校验角色。

## 通用约定

成功：`{"code":"OK","message":"成功","data":{...}}`。失败：`{"code":"SEAT_CONFLICT","message":"该座位在目标时段已被预约","data":null}`。

HTTP状态：参数400、登录失效401、角色或CSRF403、无资源／不可访问404、业务冲突409、暂时数据库故障503、其他500。主要代码见需求文档5.1。ID作为十进制字符串返回；请求仅允许声明的字段，禁止传入用户ID或注册角色。

`page`默认1，`size`默认10、范围1–100。分页返回 `records/total/page/size`。日期为 `YYYY-MM-DD`，时间为带 `+08:00` 的ISO 8601；GET参数应通过 `URLSearchParams` 编码，避免加号变为空格。

POST必须通过 `X-CSRF-Token` 提交有效令牌。请求流程：获取令牌 → 注册／登录 → 登录后重新获取令牌 → 其他写操作。浏览器同源Cookie自动发送，禁止将Cookie或令牌写入日志。

```javascript
const tokenResponse = await fetch('/api/user/csrf')
const token = (await tokenResponse.json()).data.csrfToken
// 登录密码由表单输入，不写进源码。
await fetch('/api/user/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': token },
  body: JSON.stringify({ account, password })
})
// 登录后需重新调用 /api/user/csrf。
```

## 认证

|方法与路径|字段|返回data|
|---|---|---|
|GET /user/csrf|无|csrfToken|
|POST /user/register|account、name、password|id、account、name、role=STUDENT；不自动登录|
|POST /user/login|account、password|id、account、name、role|
|GET /user/me|无|当前用户身份|
|POST /user/logout|空对象|空；注销会话|

退出仍需CSRF。会话已失效时先取匿名会话令牌，再退出，返回成功。

## 学生

|方法与路径|参数|返回data|
|---|---|---|
|GET /room/list|page、size|已启用且未删除的自习室分页；id、name、location、openTime、closeTime、enabled、deleted|
|GET /seat/list/{roomId}|startTime、endTime|roomId、目标时段、userAvailable、seats|
|POST /reserve/add|seatId、startTime、endTime、requestId|预约详情与快照，status为展示状态|
|POST /reserve/cancel|reserveId|reserveId、status、cancelledAt|
|GET /reserve/myList|page、size、可选status|个人预约分页|
|GET /ai/recommend|startTime、endTime|目标时段、mode、historyCount、preferences、seats（最多5条）|

座位字段：`id/roomId/seatNo/seatType/hasPower/isQuiet/deleted`，查询额外返回 `seatAvailable`。座位类型：`WINDOW/AISLE/MIDDLE`。状态：`PENDING/IN_PROGRESS/ENDED/CANCELLED`。

提交示例（时间须按当前日期选择未来7天内的合法时段）：

```json
{
  "seatId": "1",
  "startTime": "2026-10-16T08:00:00+08:00",
  "endTime": "2026-10-16T10:00:00+08:00",
  "requestId": "5bd8172c-8943-4313-90df-1039a1bce2ce"
}
```

UUID由客户端生成，网络失败时相同参数复用；改变参数须生成新的标识。同一标识改变参数返回409 `IDEMPOTENCY_CONFLICT`。

推荐：无有效历史时 `mode=DEFAULT`、分数0；否则 `PERSONALIZED`。`preferences`中的 `rooms/seatTypes/power/quiet`分别以 `roomId/seatType/hasPower/isQuiet`作为属性键，每项另含 `count/ratio`。候选包含座位字段以及 `seatId/roomName/score/reason`。推荐结果不保证稍后提交仍可用，零候选返回200和空数组。

## 管理员

|方法与路径|参数|
|---|---|
|GET /admin/room/list|page、size、可选enabled|
|POST /admin/room/add|name、location；openTime/closeTime可同时省略，默认08:00/22:00|
|POST /admin/room/update|roomId、name、location、openTime、closeTime|
|POST /admin/room/status|roomId、enabled|
|POST /admin/room/delete|roomId|
|GET /admin/seat/list|roomId、page、size|
|POST /admin/seat/add|roomId、seatNo、seatType、hasPower、isQuiet|
|POST /admin/seat/update|seatId、seatNo、seatType、hasPower、isQuiet（不接受roomId）|
|POST /admin/seat/delete|seatId|
|GET /admin/reserve/list|page、size、可选account/status/fromDate/toDate|
|GET /admin/statistics/daily|fromDate、toDate（必填）|

开放时间格式 `HH:mm`，按30分钟对齐。修改返回修改后资源；删除返回 `id/deleted`。创建资源不接受客户端指定ID，删除幂等；存在未结束预约时禁止停用／删除和影响预约的修改。

预约账号筛选精确匹配；日期成对传入，均包含边界，按预约开始日期筛选。统计最多367个自然日，逐日返回 `date/totalCount/cancelledCount/activeCount`，无预约日期补零；`activeCount`为全部未取消预约，不是正在使用数。

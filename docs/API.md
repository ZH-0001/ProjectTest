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

推荐：无有效历史时 `mode=DEFAULT`、分数0；否则 `PERSONALIZED`。`preferences`中的 `rooms/seatTypes/power/quiet`分别以 `roomId/seatType/hasPower/isQuiet`作为属性键，每项另含 `count/ratio`。候选包含座位字段以及 `seatId/roomName/score/reason/breakdown`。推荐结果不保证稍后提交仍可用，零候选返回200和空数组。

## 预约助手（1.1.0）

仅学生可用，沿用 Session 和 CSRF。以下均为后端路径，浏览器增加 `/api` 前缀。

|方法与路径|请求|返回data|
|---|---|---|
|GET /assistant/conversation|无|available、conversationState、messages、cards、busy、version|
|POST /assistant/chat|message、requestId（UUID）|reply、conversationState、cards、available|
|POST /assistant/reset|空对象|重置后的会话视图|
|POST /assistant/book|cardId|真实预约详情，沿用 /reserve/add 返回格式|

聊天请求仅允许两个字段；`message`为1–2000个字符，UUID绑定原始消息。在当前Session、会话版本与缓存期限内，同UUID同消息共享在途任务或重放成功／失败结果；同UUID不同消息返回409。网络失败重试原UUID，主动重试已失败请求使用新UUID。新消息与在途消息不同返回409，不排队。

`conversationState`是已提交的条件映射，键为 `startTime/endTime/roomId/seatType/hasPower/isQuiet`，值为 `{value,strength}`。时间总为HARD；属性HARD参与过滤，SOFT参与评分。前端不直接提交条件或用户ID，模型通过白名单查询工具提交SET/CLEAR增量，后端校验后合并。每轮成功后原子发布条件、消息及卡片；错误、超时、超限和会话重置不发布本轮暂存。

`messages`每项为 `{requestId,message,reply,cardIds}`；`cards`为当前会话留存卡片。聊天只返回本轮新增卡片。会话最多20个完整轮次；卡片最多100张、生成后保留30分钟；聊天去重结果最多200项、完成后保留30分钟。在途任务不会被缓存淘汰。清空、退出和会话失效均清除关联数据，并阻止迟到结果写回。

候选卡片含 `cardId/kind=CANDIDATE/seatId/roomId/roomName/seatNo/seatType/hasPower/isQuiet/startTime/endTime/score/reason/historyCount/breakdown/generatedAt/expiresAt/actionState/reservation`。首次预约有效期2分钟，`actionState`为 `READY/EXPIRED/BOOKED`；前端发生冲突可自行显示CONFLICT。过期不代表释放或占有座位，预约始终重新检查冲突。前端确认后只回传 `cardId`，不得传座位、时间、预约UUID或身份。内部UUID由服务器生成并绑定卡片；后端先查原记录，再检查首次预约时效，因此已成功的请求在30分钟保留期内可重放，包括已取消或已结束的预约。清理后的卡片统一404，不自动新建预约。

本人预约卡片 `kind=RESERVATION`，附 `reserveId`及实际查询时的预约状态。取消仍调用原 `/reserve/cancel`，仅待开始且属于当前用户的预约允许取消；状态和时间由该接口再次验证。

`breakdown`固定四项：`dimension=ROOM/SEAT_TYPE/POWER/QUIET`，`weight=40/30/20/10`，`score`为该项贡献分。`source=EXPLICIT/HISTORY/NONE`分别表示本次偏好、历史记录、暂无依据；本次偏好附 `matched`，历史项附 `historyMatches/historyTotal`，不适用字段为null。偏好匹配贡献该项满分、不匹配为0；未指定维度沿用全部已结束且未取消历史的出现比例。排序用未舍入分，同分按自习室ID、座位ID升序；显示两位小数，分项相加可能有舍入尾差。带本次偏好的结果为 `mode=EXPLICIT`；原 `/ai/recommend` 不接受偏好参数且保持原公式。

每轮最多4次模型请求、8次工具执行、60秒；同用户滚动一分钟10条、北京时间每天200条，全局最多5个实际执行任务。新请求准入后扣额度，失败也计数；重放不重复扣减。用户额度在单实例内存保存，清空与重登不重置，服务重启重置。

|HTTP / code|含义与处理|
|---|---|
|503 ASSISTANT_UNAVAILABLE|开关关闭、缺少密钥、凭据无效或模型服务不可用；原查询和预约仍可使用|
|409 ASSISTANT_REQUEST_CONFLICT|同UUID用于不同消息，修正调用逻辑|
|409 / 429 ASSISTANT_BUSY|当前对话忙／全局任务已满，稍后重试|
|429 ASSISTANT_RATE_LIMITED|用户额度已满或上游限流，稍后重试|
|504 ASSISTANT_TIMEOUT|本轮或模型请求超过截止时间|
|503 ASSISTANT_LIMIT_REACHED|达到请求／工具上限，简化问题后使用新UUID|
|409 ASSISTANT_CANCELLED|清空会话或Session失效导致取消|
|502 ASSISTANT_PROTOCOL_ERROR|回复为空、截断、未关闭思考或工具参数协议异常|
|403 ASSISTANT_TOOL_FORBIDDEN|模型请求了白名单外的工具，整轮拒绝|
|404 ASSISTANT_CARD_NOT_FOUND|不属于当前Session、已清理或不是可预约卡片|
|409 ASSISTANT_CARD_EXPIRED|尚未成功提交的卡片超过2分钟，重新查询|

服务不可用时仍可读取和重置会话，返回 `available=false`。模型回复按纯文本显示，不能以聊天内容直接触发预约或取消。

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

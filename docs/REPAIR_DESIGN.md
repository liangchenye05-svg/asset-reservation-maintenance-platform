# 维修闭环：Controller、Service、Mapper与SQL

## HTTP与权限

POST /repair-orders由当前身份创建，成功仍返回200及整数1。GET /repair-orders保留旧数组行为：status可省略，合法值PENDING/PROCESSING/COMPLETED，显式空值或非法值400。

GET /repair-orders/page默认page=1、pageSize=10，pageSize为1–100；status/deviceId/keyword组合查询，keyword最多100字符，OPEN聚合未完成单。返回total/page/pageSize/records。普通USER的owner来自服务器，不可用参数扩大范围。

GET /repair-orders/{id}、/{id}/history仅本人/ADMIN。GET /repair-orders/{id}/maintenance-impact和PUT /{id}/process、/{id}/complete仅ADMIN。匿名401，角色/归属拒绝403，不存在404，非法参数/重复状态操作400。错误保留文本/框架响应，未统一包装改变旧契约。

完成接口可选请求体：{"reviewNote":"实际核对情况与后续协调安排"}。CompleteRepairOrderRequest在入口trim并校验提供的说明为1–500字符；需要人工核对但缺说明409。真实操作者来自认证上下文，不接受actorId/historyComplete等请求伪造。

## Service事务与锁

RepairOrderService的创建、受理、完成使用READ_COMMITTED事务，先锁设备，再重读工单状态。设备锁与预约操作共享，避免维修与审批分别判断后写入矛盾结果。条件UPDATE再次约束旧状态，以影响行数确认成功；这不是跨刷新创建请求的幂等键。

创建显式写history_complete=1及CREATED；受理保存首次进入维修的可靠原状态并写PROCESSED；完成写COMPLETED和review_note，countOpenForDevice为0才调用恢复SQL。任一历史/设备写失败使事务回滚，不以补造记录通过测试。

## Mapper和V3元数据

列表/详情返回historyComplete和manualReviewRequired。后者由PROCESSING与所属设备maintenance_restore_blocked计算，页面不决定授权。

beginMaintenance在原MAINTENANCE或已暂停时保留原快照；否则记录原状态再进入维修。restoreBeforeMaintenance要求当前MAINTENANCE、快照非空、暂停为0。设备级暂停在人工完成后持续存在，兄弟工单不能通过后来的快照间接恢复。

V3对原工单按真实链数量和ID顺序分类，更新元数据时保持update_at；没有CREATED的旧单后来出现PROCESSED/COMPLETED仍不完整。迁移不改业务状态、不插入历史。review_note仅来自新真实操作。[迁移影响](DATABASE_MIGRATIONS.md)。

## 学习与验证

可逐行讲解RepairOrderController → DTO → RepairOrderService → RepairOrderMapper/DeviceMapper → V3 SQL。重点说明设备锁与条件UPDATE分别解决什么、为什么历史必须同事务、为什么有历史不代表历史完整、为什么最后一单也不能越过暂停。

测试覆盖旧/新工单、缺快照、兄弟工单、重复操作、权限、历史写失败回滚和实际锁等待；数量及截图限制以[TESTING](TESTING.md)为准。无指派、重开、评论附件或解除暂停功能。

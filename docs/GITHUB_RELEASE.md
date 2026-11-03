# V3匿名求职版交付边界

维护者Sweetest，提交身份使用已确认的GitHub noreply。当前保留原9个本地提交和62个已跟踪文件，新增/修改仅在工作区，尚未暂存、提交、推送或公开；不编造本轮新提交ID或未来提交数。

原9提交中的3处无私人身份、邮箱或密码的历史绝对路径已审查并由用户明确接受保留；本轮不改历史，也不在新文档扩散这些路径。主项目私人Git历史、身份映射、reflog或原.git压缩包绝不迁入。

本轮按批准清单同步47项代码/测试/模板，重写11份公开文档；保留原.gitignore、DEV-030历史说明及四张旧截图。旧图明确为历史预览，不作为V3当前画面。所有验证在仓库外的干净副本和合成数据库进行，实际数量见[TESTING](TESTING.md)。Docker保持未验证。

公开范围是精确白名单中的源码、测试、安全示例、迁移、轻量前端和公开说明。排除.idea/.run/.env、私人application.properties、target、verification、数据库dump/备份、真实日志/XML/HAR、账号文件、审查脚本/报告及恢复材料。gitignore不替代白名单，也不防止JAR打入私人配置或网页拖拽上传。

原忽略规则保持；新profile、新截图和任意命名SQL/审查文件须单独检查。安全的example/docker/isolated-test profile和schema/V1/V2/V3仍需保留。不要用整目录暂存、强制添加、reset/clean或复制私人.git来整理成果。

可只读审查：

```powershell
git status --short --untracked-files=all
git diff --stat
git diff --cached --name-status
git log --format=fuller -9
git ls-files -ci --exclude-standard
```

暂存、本地提交、远程设置和公开分别需要后续明确授权。每步先逐路径、完整差异、author/committer和敏感信息复查，不附私人身份处理报告或旧新提交映射。

仓库简介可用：企业资产预约与运维工单协作平台，基于Spring Boot/MyBatis/MySQL，包含JWT权限、预约冲突、设备行锁、多工单状态协同、历史缺口与人工核对、V3迁移和可核查测试。个人实践项目，非生产企业案例，不宣称吞吐量或商业使用规模。

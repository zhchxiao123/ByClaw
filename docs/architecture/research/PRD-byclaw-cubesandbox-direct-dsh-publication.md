# PRD 本地管理与旧发布关闭记录

- 日期：2026-10-14。
- 管理方式：用户明确要求只使用本地文件，不发布issue。
- 权威需求：[.scratch/byclaw-cubesandbox-direct-dsh/PRD.md](../../../.scratch/byclaw-cubesandbox-direct-dsh/PRD.md)。后续拆解也只记录同feature目录的本地文件。
- 旧发布：[beyonai/ByClaw #301](https://github.com/beyonai/ByClaw/issues/301)，已关闭，不再作为需求跟踪入口。
- 标题：[PRD] 保留 ByClaw 前端、直连 DSH 与 CubeSandbox 生命周期改造。
- 原正文位置已保留跳转索引，完整PRD迁入本地tracker规范目录，不维护双份正文。
- 原发布正文 SHA-256：`f2146d8f7c0321e13e0b0acb09d10a0ef7b1139b64677d1426dc0b21ca5ba958`。这是关闭前的历史版本，本地PRD已修改为本地管理约定。
- 需求内容保留：108 条连续编号用户故事；28 项验收场景；7 个规定章节。
- 用户已确认验收面：现有 ByClaw Chat 页面与对外业务 API，真实 DSH/Cube；受理持久化、检查点等故障另做注入。
- issue 状态：Closed，已于 `2026-10-14T15:59:15Z` 读回核验。关闭不是完成开发；未删除其线上历史正文。

## 后续约定

不再补加标签、创建开发子issues或恢复线上发布。早先的标签权限阻断已不属于待办。后续需求、任务和验收证据使用本地文件，除非用户再次明确要求发布。

## 工作区边界

文档保持未提交；原工作区分支未切换。没有修改业务代码、数据库、生产配置或现有服务。已有研究文档保持保留。发布只创建此父级issue，未拆分开发子issues、未修改早期CubeSandbox需求 #117。

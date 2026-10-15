# 1.1.0 候选版本与发布准备

当前为未提交、未发布的工作区候选版本，正式验收尚未全部完成。

拟发布变化：最低 Maven JVM 从 Java25 降至 Java17，采用单一 ASM9.11；增加独立原生
和 Spring AOT 样例、来源分类及运行环境记录；修复迁移验证暴露的权限覆盖、参数类型
语义、未知条目保护和输出路径边界问题。原已发布的 1.0.0 不覆盖。

已完成：

- [x] 本机 Java17/21/25 核心测试、六个 Maven 消费组合和普通 OpenJDK17 补充验证。
- [x] 普通 Native 与 Spring AOT 原生 A/B/C、Java25 业务字节码验证。
- [x] 两个独立样例离开插件 checkout 运行；java-pi 实际项目试用通过。
- [x] 候选 JAR/POM 和源码清单保存；README 说明运行、构建和业务目标版本的区别。

后续交付门禁：

- [ ] IDEA Maven Runner / Project SDK 手动演练，见 [idea-walkthrough.md](idea-walkthrough.md)。
- [ ] 审查并提交当前分支，记录 candidateCommit；现有验证明确绑定冻结产物哈希。
- [ ] 运行远程 CI，确认 Linux 与 macOS jobs 的实际结果和上传证据；不能用 YAML 检查代替执行。
- [ ] 在完成候选提交和门禁后，按仓库发布流程生成新版本制品并记录发布产物哈希。
- [ ] 实际 tag / push / PR / 发布按任务授权单独执行；本轮均未执行。

当前结果总表见 [migration-results.md](migration-results.md)。

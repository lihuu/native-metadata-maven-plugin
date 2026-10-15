# 1.1.0 候选版本与发布准备

候选实现已提交：`c20b952726128af25b757ce7325db7039f84f2ce`；1.1.0 尚未发布，正式验收尚未全部完成。

拟发布变化：最低 Maven JVM 从 Java25 降至 Java17，采用单一 ASM9.11；增加独立原生
和 Spring AOT 样例、来源分类及运行环境记录；修复迁移验证暴露的权限覆盖、参数类型
语义、未知条目保护和输出路径边界问题。原已发布的 1.0.0 不覆盖。

已完成：

- [x] 本机 Java17/21/25 核心测试、六个 Maven 消费组合和普通 OpenJDK17 补充验证。
- [x] 普通 Native 与 Spring AOT 原生 A/B/C、Java25 业务字节码验证。
- [x] 两个独立样例离开插件 checkout 运行；java-pi 实际项目试用通过。
- [x] 远程 CI 执行并全绿：run `38684961127`（head `d0f1f82`），19/19 jobs PASS，含 acceptance summary。
- [x] 候选 JAR/POM 和源码清单保存；README 说明运行、构建和业务目标版本的区别。

后续交付门禁：

- [ ] IDEA Maven Runner / Project SDK 手动演练，见 [idea-walkthrough.md](idea-walkthrough.md)。
- [x] 候选实现已提交并记录 source candidate commit；源码/POM 与已验证产物清单一致。
- [x] 运行远程 CI，确认 Linux 与 macOS jobs 的实际结果和上传证据：run `38684961127`，19/19 绿，
      Linux（六个消费组合、O17、MR、N17/N25、S17/S21）与 macOS 冒烟的 evidence artifact 均已上传。
- [ ] 在完成候选提交和门禁后，按仓库发布流程生成新版本制品并记录发布产物哈希。
- [ ] 完成剩余门禁后执行 tag / 发布；本轮用户已授权提交、推送和创建 PR，不执行合并或发布。

当前结果总表见 [migration-results.md](migration-results.md)。

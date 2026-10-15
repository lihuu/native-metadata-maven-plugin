# JDK 17 / 单一 ASM 实施 Plan

状态：本机实现与验证已完成，正式交付仍有未执行项。勾选项表示已有执行证据；完成情况与证据见 [validation/migration-results.md](validation/migration-results.md) 与 [validation/baseline.md](validation/baseline.md)。

依据：[兼容性与验证 Spec](jdk17-compatibility-spec.md)。行为边界和最终验收以 spec 的
AC-01～AC-15 为准；本计划负责说明修改顺序、交付文件、验证方法和阶段退出条件。

已确定：单一 ASM 9.11；最低 Java 17；插件首选用 GraalVM 25 构建；主要用户环境为
GraalVM 17、21、25；独立 Maven 项目消费插件坐标；不按 JDK 维护两套扫描器。

## 1. 执行顺序与进度

| 阶段 | 对应 spec | 当前状态 | 退出条件 |
| --- | --- | --- | --- |
| P0 准备环境与证据 | M0 | 完成 | 固定工具链可用，基线和缺口已记录 |
| P1 建立独立消费测试 | M1 | 完成 | 本次产物隔离消费，旧实现的 JDK 17 失败可复现 |
| P2 迁移扫描器与编译目标 | M2 | 完成 | ASM 结果正确，生产代码和核心测试在 17 可运行 |
| P3 完成跨 JDK / Maven 验证 | M3 | 本机完成，远程 CI 未运行 | 同一产物在六个主要组合和补充环境通过 |
| P4 验证原生反射应用 | M4 | 完成 | N17/N25 的基线、裁剪、负向对照全部符合预期 |
| P5 验证 Spring Boot AOT | M5 | 完成 | S17/S21 的配置发现、生命周期及 HTTP 对照通过 |
| P6 完成实际使用与交付 | M6 | 进行中 | java-pi 与终端试用完成；IDEA、发布与远程 CI 待执行 |

依赖关系为 `P0 → P1 → P2 → P3 → P4 → P5 → P6`。可以提前准备 Spring 样例，
但不能在缺少产物兼容或原生运行证据时，把后续阶段标为完成。

各阶段采用可单独审查的提交，保留相应的验证记录。当前已有的 spec、README 改动必须
保留，不因开始实施或切换工作目录而丢失。实施前记录分支和工作区状态；本计划不执行
commit、push、打标签或发布。

## 2. P0：准备环境与证据

交付文件：

- `docs/validation/toolchains.json`：固定工具版本、vendor、平台、下载地址、SHA-256。
- `docs/validation/baseline.md`：提交、现有测试结果、已知缺口和失败证据。
- `target/compat/baseline/`：本次执行的原始日志，不提交构建输出。

任务：

- [x] P0-1：核对 spec 的 GraalVM 17/21/25 分发与 Linux runner，准备真实 Java 17。
- [x] P0-2：固定 Maven 3.9.9、3.10.0 和补充验证用普通 OpenJDK 17。
- [x] P0-3：在 GraalVM 25 保存当前 `mvn -B -ntp verify` 结果；区分实际 native 执行与跳过。
- [x] P0-4：列出 `java.lang.classfile`、Java 17 之后的 API、固定 25 的测试，以及现有集成测试的隐式环境依赖。
- [x] P0-5：确认 ASM、Invoker、Spring Boot 固定依赖可解析；不得悄悄换成 latest 或其他版本。

完成条件：环境能在干净目录启动，工具版本与锁定文件相符。某个分发不可用时，修订该组合
并记录原因；不得把缺失工具视为验证成功。真实试用项目可稍后选择，不阻塞 P1～P5。

## 3. P1：先建立能暴露问题的独立消费测试

主要修改：`pom.xml`、`src/it/`、`examples/plain-native/`、
`scripts/verify-compatibility.sh`；移出 `SampleProjectIntegrationTest` 中的自安装逻辑。

任务：

- [x] P1-1：建立可单独使用的 plain Maven 项目，包含真实元数据和运行时输入控制的动态反射。
- [x] P1-2：加入 Invoker 的产物暂存、版本过滤、结果校验；集成测试移到 `package` 之后。
- [x] P1-3：实现接收插件 POM/JAR 的消费脚本，复制样例到 checkout 外，使用隔离 Maven 仓库。
- [x] P1-4：在子 Maven 内断言实际 JDK/Maven 版本；记录被测插件版本和 SHA-256。
- [x] P1-5：外部进程增加超时、退出码检查、失败日志和子进程清理；缺工具明确失败。
- [x] P1-6：用尚未迁移的插件，在真实 JDK 17 执行 `analyze` 和 `prune`，保存加载失败证据。

消费脚本接口按 spec 实现，最低接收 `--artifact`、`--pom`、`--maven-home`、
`--java-home`、`--case`。它只安装并消费传入产物，不能重建插件、读取插件测试 classpath，
或借用个人仓库中同坐标旧 JAR。

完成条件：同一当前插件在 25 下能执行，在 17 下因已知字节码/API 不兼容失败；
这个失败必须来自真实的插件调用，而不是下载失败或空项目跳过。迁移前的失败作为负向证据
保存；阶段性合并若暂不启用兼容门禁，须明确标记待启用，不能长期忽略失败。

## 4. P2：迁移扫描器与编译目标

主要修改：`ClassfileScanner.java`、`pom.xml`、扫描/分析相关单元测试及字节码 fixtures。
尽量保持 `BytecodeInventory` 等现有返回结构。

任务：

- [x] P2-1：为成员、访问标志、重载/数组、record、两类注解、嵌套 class 引用、invoke/field 指令和来源定义独立期望值。
- [x] P2-2：用 JDK 25 预生成目标为 17、21、25 的真实 class/JAR；单独打包，供低版本运行测试读取。
- [x] P2-3：用 ASM visitor 替换 Class-File API，保留现有反射调用点和 classpath 首项优先行为。
- [x] P2-4：生产编译目标统一为 17，加入 ASM 运行依赖，设置插件最低 Java 声明。
- [x] P2-5：核心测试源码和动态编译样例适配 17，移除 `getFirst()` 等高版本 API；高版本输入测试使用 P2-2 产物。
- [x] P2-6：运行语义回归，检查不支持版本/损坏输入的报错；审计没有业务类加载和 JDK 内部 API。
- [x] P2-7：在 GraalVM 17、21、25 运行核心测试；检查发布 class 为 major 61 和依赖有效路径兼容 17。

验证重点：不能只把旧扫描结果全量保存为快照；样例预期必须可从源码语义核对。已有
public/declared、构造器查询权限测试必须继续通过。发现无参重载或条件处理等既有问题时，
单独记录和修复影响验收的缺陷，不把错误当作迁移必须保留的行为。

完成条件：B25、U17/U21、X17 通过；P1 中同一个 JDK 17 消费用例由失败变为成功。
切换后不保留需要加载 JDK 25 类的扫描器路径。

## 5. P3：同一插件产物跨环境验证

主要修改：消费脚本、目录/JAR/MR-JAR fixtures、配置与输出边界测试、CI 工作流。

任务（P3-1/P3-7 的 workflow 已实现，但远程执行和上传结果尚未产生）：

- [ ] P3-1：只在 build job 用 GraalVM 25 构建插件，将 POM/JAR、SHA-256 和高版本 fixtures 传给消费 jobs。
- [x] P3-2：执行 GraalVM 17/21/25 × Maven 3.9.9/3.10.0 六个组合，每个都运行两个 goal。
- [x] P3-3：执行普通 OpenJDK 17 补充消费测试和 macOS / GraalVM 21 冒烟。
- [x] P3-4：验证 MR-JAR 的有效条目、重复类、资源同名但所有权不同等来源处理。
- [x] P3-5：验证源文件哈希、dry-run、候选文件、两种元数据格式、重复执行及失败不部分应用。
- [x] P3-6：验证输出目录、路径穿越及符号链接边界；暴露缺陷时做针对性修复并重跑受影响用例。
- [ ] P3-7：上传环境、日志、断言和消费哈希；在 CI 启用已实现的兼容必需门禁。

完成条件：R17/R21/R25、O17、MR、MAC、X17 有通过记录；六个主要组合的插件哈希
与 build job 一致。低版本 job 不能用自己重新编译出来的 JAR 替换被测产物。

## 6. P4：普通 Native 项目的因果验证

主要修改：`examples/plain-native/`、`src/it/` 驱动、`native-it` profile 和 CI native jobs。

任务：

- [x] P4-1：plain 项目提供分析、dry-run、应用裁剪及基线/实验/负向对照入口，附独立 README。
- [x] P4-2：确认业务操作的类名/方法名来自运行时输入，不能被静态常量反射分析替代。
- [x] P4-3：在同套 GraalVM 17 下执行 Maven 和 native-image，跑完 A/B/C 三组。
- [x] P4-4：在同套 GraalVM 25 下重复，业务字节码目标为 25。
- [x] P4-5：确认实际消费的全部配置，而非只看一个 JAR；保存原生工具版本、命令、二进制哈希和业务输出。

三组验收：

| 组别 | 操作 | 必须得到的结果 |
| --- | --- | --- |
| A | 原始配置，不裁剪 | 原生编译、运行成功 |
| B | 使用插件裁剪后的配置 | 预期裁剪生效，运行结果与 A 相同 |
| C | 独立副本移除一个必要反射登记及其所有副本 | 同一动态操作明确因缺少登记失败 |

完成条件：N17/N25 均通过。C 如果仍然成功，必须排查常量推断、重复配置和缓存，
修正样例后再验收；不能仅凭 A/B 成功认为裁剪安全。缺少 native-image 直接失败。

## 7. P5：独立 Spring Boot Native 项目

主要修改：`examples/spring-boot-native/`、两个 Mojo 及共用配置来源逻辑、
`spring-native-it` profile 和 CI Spring native jobs。

任务：

- [x] P5-1：固定 Boot 3.5.16 / Native Build Tools 0.10.6，提供 REST、Jackson DTO/record 和需要 RuntimeHints 的动态操作。
- [x] P5-2：建立真实 AOT 基线，保存 effective POM、生成资源清单与原生运行 HTTP 结果。
- [x] P5-3：支持只读生成配置发现及来源分类，必要时实现 spec 的 `generatedMetadataDirectories`；只用实际消费来源支持裁剪。
- [x] P5-4：插件在样例中绑定 `prepare-package`；从日志证明 AOT、资源进入消费路径、裁剪、native 的顺序。
- [x] P5-5：用真实 AOT 登记覆盖一条自持冗余配置，验证预期去重和 changelog；普通零变更结果如实报告。
- [x] P5-6：在同套 GraalVM 17 和 21 下，分别执行独立项目的 A/B/C 及相同 HTTP 请求。
- [x] P5-7：校验生成源、源码和依赖 JAR 未改写；确认所有原生输入中的有效权限符合预期。

完成条件：S17/S21 通过。两个样例均能复制到插件 checkout 外，作为普通 Maven 项目运行；
Spring 样例不继承插件 POM、不在其 reactor 中，也不读取插件源码或测试输出。

## 8. P6：实际使用、文档与交付

主要修改：两个样例 README、根 README、`docs/validation/` 结果记录及最终 CI 验收汇总。

任务：

- [x] P6-1：维护者从干净环境按 README 使用两个样例，记录遇到的问题并修正文档或实现。
- [ ] P6-2：在 IDEA 用 GraalVM 17 配置项目 SDK / Maven Runner，完成同一使用流程并记录实际运行版本。
  `NOT_RUN`：本机没有安装 IntelliJ（`/Applications`、`~/Applications`、Toolbox 均无），也没有可用的
  computer-use 工具，无法执行该手动演练；操作手册见 [validation/idea-walkthrough.md](validation/idea-walkthrough.md)。
- [x] P6-3：选择至少一个真正准备采用插件的项目试用，保存脱敏的输入、命令、报告、变化和行为验证。
  用户指定 `java-pi`（提交 `a4381a2c`），在隔离开的临时副本上以冻结制品完成基线原生构建 + MCP 闭环、
  dry-run（102/102 自持类型解析，0 变更）、apply + 原生构建 + MCP 闭环；记录见 [validation/java-pi-trial.md](validation/java-pi-trial.md)，脱敏夹具见 [validation/java-pi-native-smoke.py](validation/java-pi-native-smoke.py)。
- [ ] P6-4：候选提交跑齐 spec 矩阵，逐条给 AC-01～AC-15 附证据；未跑项明确为 `NOT_RUN`。
- [x] P6-5：更新当前运行要求、兼容表、AOT 生命周期配置和报错说明；区分测得收益与尚无证据的性能说法。
- [x] P6-6：准备新版本的变更说明和发布检查，不覆盖 `1.0.0`；实际发布单独执行。

实际试用已选择 java-pi 并通过；详见验证记录。AC-12 的 IDEA 子项仍为 NOT_RUN，
AC-14 的候选实现已提交，但发布尚未执行，不能声称所有交付验收结束。

## 9. 执行纪律与完成判断

- 每阶段记录修改文件、命令、结果、产物哈希、尚未覆盖项；计划中的命令不得记作已运行。
- 快速单元验证通过后再进入耗时 native 验证；新改动影响已有证据时重跑对应路径。
- 迁移期间暴露安全问题先保存最小失败用例、针对性修复，再恢复相关应用裁剪验证。
- 不引入双后端、自动可达性裁剪、Maven 4 或 Java 8/11 支持等本次未选择的扩展。
- CI、脚本和本地运行共用相同样例与断言，避免人工演示项目和自动验证项目逐渐分叉。
- 最终完成依据是 spec 的 15 条验收证据，不是测试数量增加、某一次绿色构建或文档已经写完。

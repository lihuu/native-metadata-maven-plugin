# JDK 17 兼容与实际使用验证 Spec

日期：2026-10-15

状态：实现与本机验证完成，java-pi 实际项目试用已通过；IDEA、远程 CI、候选实现已提交，发布仍未执行。逐条证据见 [validation/migration-results.md](validation/migration-results.md)。

基线提交：`109ceaf`。本文中的“计划执行项”描述的是验收设计；实际执行状态以 validation 目录为准。

实施顺序与任务追踪见 [JDK 17 / 单一 ASM 实施 Plan](jdk17-migration-plan.md)。

## 1. 要交付的结果

主要用户是已经采用 GraalVM Native Image 的业务项目。标准使用方式是用同一个 GraalVM
JDK 运行 Maven、编译业务代码，并调用该发行版的 `native-image`；重点验证 GraalVM
Java 17、21、25 的完整流程，不要求用户为了插件改变已有的原生构建 JDK。

插件自身继续以 GraalVM JDK 25 作为首选开发、构建环境，但发布的插件和运行依赖满足
Java 17 运行基线。普通 OpenJDK 的分析/裁剪能力保留为补充兼容验证，不把“普通 JDK +
另一套 GraalVM”的分离环境作为默认接入方案。

兼容性必须由实际用户流程证明：安装本次产物、执行 Maven goal、检查报告与打包结果，
并在 Native Image 中验证运行行为。仅修改 `release`、检查 class 版本或在 JDK 25 上
跑单元测试，都不能单独构成兼容性验收。

本次工作包含扫描器迁移、测试基础设施、Spring Boot 集成样例及使用文档；
本 spec 本身不代表这些工作已经实现。

## 2. 已有证据与缺口

| 现状 | 已有证据 | 不能由此推出的结论 |
| --- | --- | --- |
| 插件与编译器配置的 `release` 都是 25 | `pom.xml` | 不能运行在 JDK 17、21 |
| 扫描器直接使用 `java.lang.classfile` | `ClassfileScanner.java` | 不能仅修改 POM 完成降级 |
| 当前 35 个测试在 GraalVM JDK 25 上通过 | 本次会话执行 `mvn -B -ntp verify` | 没有 JDK 17、21 的完整运行证据 |
| CI 只在 macOS / GraalVM 25 上执行 `clean test` | `.github/workflows/ci.yml` | 没有普通 OpenJDK 验证，也没有独立的 `verify` 门禁 |
| 示例集成测试固定插件版本 `1.0.0` | `SampleProjectIntegrationTest` | 本地已有同坐标插件时可能验证旧产物 |
| 示例与扫描测试固定 `--release 25` | 三个现有测试类 | 不能直接在 JDK 17 上运行这些测试 |
| 未安装 `native-image` 时直接跳过 native 分支 | `SampleProjectIntegrationTest` | 测试通过不等于原生编译、执行已经发生 |
| 示例使用常量类名和方法名反射 | 同上 | 编译器可能推断所需登记；尚未证明运行成功依赖被测试的配置 |
| 自持配置默认只发现 `src/main/resources` | 两个 Mojo 的 `resolveOwnedConfigs()` | 尚未证明 Spring AOT 生成配置被发现并正确参与分析 |
| MR-JAR 按 `Runtime.version()` 选择条目 | 两个 scanner | 不同 Maven JVM 可能看到不同有效内容，不能一律要求报告相同。注意 JAR 规范不允许 `META-INF/` 下的资源版本化：`META-INF/versions/N/META-INF/native-image/...` 不是任何 JVM 会读取的登记，不能当作覆盖自持配置的依据 |

补充的局部实验证明：当前插件在 JDK 21 上加载报 `UnsupportedClassVersionError`；
JDK 21 上的 ASM 9.8 可以读取同一个 Java 25 class。该实验不是本项目 JDK 17 兼容验收。

实施前本机仅发现 JDK 21 和 25，未配置 JDK 17；现已准备并实际验证 GraalVM/Temurin 17。必须使用真实 JDK 17 环境，
不能用 JDK 25 的 `--release 17` 代替。

## 3. 支持契约

| 维度 | 本次承诺 | 边界 |
| --- | --- | --- |
| Maven 主要运行 JDK | GraalVM Java 17、21、25 | 本次不承诺 8、11、preview 特性及所有中间版本 |
| 补充运行环境 | 普通 OpenJDK 17 的分析/裁剪 | 验证无 GraalVM 私有依赖；不要求在该环境构建原生程序 |
| Maven | 固定验证 3.9.9、3.10.0 | 暂不降低 3.9.9 基线；不自动宣称支持 Maven 4 |
| 我们构建插件的 JDK | 首选 GraalVM 25；源码及核心测试也须在 17、21 编译运行 | 构建工具 JDK 与发布产物最低 JDK 分开 |
| 发布的插件 class | Java 17，major version 61 | 本次不引入插件自身的 Multi-Release JAR |
| 被扫描的应用 class | 普通 Java 17、21、25 字节码 | 读取不等于加载执行；不据此承诺应用可在低版本 JVM 运行 |
| Native Image | 默认使用运行 Maven 的同一套 GraalVM 工具链 | 支持显式工具路径，但不强制双 JDK 配置 |
| Spring Boot | 固定 3.5.16，业务代码目标 Java 17 | 是本次验收样例，不等于认证全部 Spring Boot 版本 |

标准 Native Image 项目中，IDEA 项目 SDK 和 Maven Runner 均推荐使用该项目选定的
GraalVM JDK；例如 GraalVM 17 项目不必为了本插件改用 25。只执行分析与裁剪时，
不得要求额外的扫描器 JDK。IDEA 项目 SDK 与 Maven Runner JRE 实际上可分别配置；
终端的 `mvn --version` 也不能代替 IDEA 内 Maven 进程的实际版本证据。

## 4. 实现方案

### 4.1 单一 ASM 扫描实现

将 `ClassfileScanner` 从 JDK Class-File API 迁移到 ASM，继续返回现有的
`BytecodeInventory`、`ClassInventory` 等数据结构。保留类名与方法签名，
避免在本次兼容迁移中扩大公共接口变化。

运行依赖固定使用 ASM 9.11；仅使用实际需要的模块，基础扫描优先只引入 `asm`。
实施时核对该固定版本的 Java 17 可运行性并锁定依赖。POM 目前在
`maven-plugin-plugin` 下配置的 ASM 是构建工具依赖，不是我们的插件运行依赖，
不能把它当成扫描器已经具备 ASM 的证据。

不维护“JDK 17 用 ASM、25 用 Class-File API”两套后端，不启动 JDK 25 子进程。
原生编译器可独立选择工具链，但扫描器本身只在当前 Maven JVM 内读取字节。

选择单一 ASM 的代价是增加一个运行依赖，并由维护者跟进新 class 格式的解析器版本。
Class-File API 的主要优势是与 JDK class 格式同步演进，以及提供标准化的读取、生成和
转换模型；它不是 Native Image 专用分析 API，也不直接提供可达性或安全裁剪结论。
它在 JDK 22、23 预览，24 正式定稿，无法覆盖本项目的 Java 17 运行基线。

当前所需的成员、注解、record 和直接字节码引用均可由两种方案提取；选择 ASM 不代表
放弃这些功能。ASM visitor 与 Class-File API 的模型式接口需要不同的实现代码，
但不能由此推断哪一种一定更准确、更快或更省内存；性能结论必须来自同输入测量。
如果未来出现 ASM 无法满足的具体能力或维护成本，再以独立设计变更评估新后端。

| 现有提取行为 | ASM 实现要求 |
| --- | --- |
| 类名、来源、字段、方法与访问标志 | `ClassReader` / visitor 提取，保留 JVM descriptor |
| 参数类型、构造器、重载及数组 | 由 descriptor 精确解码；构造器与普通方法保持区分 |
| Record 类型及组件 | 读取 Record 标志及 record component，不加载业务类 |
| 运行时可见/不可见注解 | 两类均保留；继续递归读取嵌套注解和数组里的 class 引用 |
| 方法调用与字段访问 | 保留 owner、name、descriptor、opcode、caller 和 source |
| 反射调用点 | 保留现有识别集合，不把调用点发现描述为目标反射成员解析 |
| 重复类 | classpath 中先出现者生效，重复项不重复统计 |
| 目录、JAR、ZIP、module-info | 保留现有支持，module-info 不作为业务类分析 |

`invokedynamic`、方法句柄和全程序可达性分析不在本次扩展范围内；迁移不能降低现有
直接 invoke/field 指令及注解识别能力。对既有扫描器没有覆盖的行为，文档必须明确。

遇到损坏 class 或超出承诺范围、解析器不支持的 class 版本时，错误应包含来源和原因；
不得静默跳过后继续输出“可安全裁剪”的结果。`prune` 应在分析成功后才应用配置。

### 4.2 编译与依赖

1. 将唯一生效的生产编译目标设为 `maven.compiler.release=17`，删除编译器配置里重复写死的 25。
2. 扫描生产代码和所有测试，替换 `List.getFirst()` 等 Java 17 不具备的 API。
3. 普通单元测试的动态编译样例默认 `--release 17`；高版本 class 另行预生成。
4. `maven-plugin-plugin` 显式配置 `requiredJavaVersion=17`，并确认生成的 `plugin.xml`。
   这个声明不能代替真实 JVM 验证，尤其不能假定 Maven 3 一定会提前友好拦截低版本 JDK。
5. 审计插件运行依赖树，排除 Java 17 生效路径上的高版本 class、新 JDK API 和 JDK 内部 API。
   对依赖 MR-JAR 的高版本专用目录，按 JVM 17 的有效条目审计，不能只扫描全部 class 后误判。
6. 验证普通 OpenJDK 即可加载插件；不保留 GraalVM 私有运行依赖。

迁移中的 differential test 可将现有 JDK 25 扫描结果作为参考，但预期结果还须从样例语义
独立定义；不能把旧实现的遗漏或错误固化为兼容标准。最终发布物不得包含旧的 JDK 25 后端。

### 4.3 配置来源与 Spring AOT

保持以下边界：

- 自持源配置默认仍来自 `src/main/resources/META-INF/native-image`；保留 `ownedConfigs` 显式配置。
- 依赖和 Spring AOT 生成的配置作为只读来源参与报告及覆盖判断，不自动变成可改写的自持配置。
- 如生成目录尚未进入项目 classpath，通过新增可选参数 `generatedMetadataDirectories`
  传入只读目录，不要求用户用 `classpath` 覆盖掉全部正常依赖解析。
- 为 `analyze`、`prune` 共用生成来源发现逻辑；显式配置目录不存在时失败，避免悄悄漏读。
- 来源识别必须结合物理位置和所有权，不能只因 JAR 中资源路径与自持路径相同就认定是本项目副本。
- 单个物理来源不得因同时从生成目录和输出目录发现而虚增覆盖计数；保留两者关系和实际消费位置。
- 只有实际原生构建会消费的来源才能支持删除自持登记；编译期可见但未进入原生构建的
  provided/test 依赖、未消费的生成目录只能作为分析信息，不能直接作为裁剪依据。

Spring Boot 样例必须记录真实顺序：

`业务编译 → Spring process-aot → 生成资源进入消费路径 → 本插件分析/裁剪 → 打包或 native-image`

Spring 集成示例将本插件显式绑定到 `prepare-package`，并在固定版本的 effective POM、
Maven 执行日志和 Native Image 输入路径上证明上述先后关系。保留普通项目现有的
`process-classes` 默认阶段；不凭同阶段插件声明顺序推测 Spring 场景一定正确。

若生成目录和 `target/classes` 都被 Native Image 消费，不能只检查普通 JAR 中的一份文件。
需要核对所有有效配置的权限并集，证明预期变更确实进入原生构建。

### 4.4 裁剪安全与报告契约

不增加“没有普通引用就自动删除”的规则。普通字节码引用、追踪缺席、绑定注解提示都不能
单独证明成员不被使用。新增 ASM 后端不改变这一边界。

必须保持并验证：条件不匹配不覆盖、调用权限强于查询权限而反向不成立、构造器与方法区分、
declared-only 不覆盖含继承成员的 public 范围，以及未知语义不参与激进覆盖判断。
对未知字段（含成员层级属性）或不支持的元数据结构，要原样保留或明确拒绝改写，不能先丢失再序列化。

分析报告应保留来源、扫描范围和不确定性；零变更是合法结果，但“跳过未执行”不能显示成
“成功分析且无需变更”。已有的无参/同名重载统计及规则 condition 校验问题仍是待验证缺口，
不得因本次更换解析器就标记已解决；若影响验收场景或裁剪安全，须先修复再放行。

## 5. 测试基础设施：先消除旧产物和跳过造成的假通过

### 5.1 移出 Surefire 中的自安装集成测试

将 `SampleProjectIntegrationTest` 的外部 Maven/native 构建迁入 Maven Invoker 集成测试。
生产单元测试仍使用 Surefire；Invoker 在 `package` 之后运行，不能在 `test` 阶段递归
启动 `mvn install -DskipTests` 来安装被测插件。

固定使用 Maven Invoker Plugin 3.10.1；`install` 在 `pre-integration-test` 暂存本次产物，
`run` 在 `integration-test` 执行固定目录的 `src/it` 样例并验证结果。
样例 POM 用 `@project.version@` 过滤，禁止固定 `1.0.0`。

每个测试单元使用独立目录，例如 `target/compat/<case-id>/{work,repo,evidence}`。
被测插件的 POM、JAR 和插件运行依赖必须在执行前暂存完成，其他依赖可按锁定配置下载。
不得从个人 `~/.m2` 借用同坐标被测插件；CI 缓存也不能替代暂存步骤。

“用 25 构建、低版本消费”测试从 build job 下载同一份插件产物，在 JDK 17、21、25 job
中暂存到各自仓库。低版本 job 不重新构建插件来替换这个产物。每个 job 检查并记录 JAR
SHA-256 与 build job 完全一致。

跨 job 暂存须处理完整 Maven POM/依赖与插件描述；不能只把 JAR 放到一个目录就宣称完成
Maven 消费测试。可用 `install:install-file` 明确安装传入 POM/JAR，但依赖解析仍须验证。

### 5.2 独立消费项目是硬性验收条件

建立两个能单独打开、构建和运行的 Maven 项目：`examples/plain-native` 与
`examples/spring-boot-native`。前者验证动态反射和规则/去重，后者验证真实 Spring AOT
与 HTTP 行为。它们不继承插件项目的 POM，不加入插件的 reactor/module；Spring 项目
可以使用固定的 Spring Boot parent。两个项目只通过 Maven 插件坐标消费待测产物，
不能将插件源码、`target/classes` 或测试类直接加入应用 classpath。

CI 将这些项目复制到插件 checkout 之外的临时工作目录，并在只接收插件 POM/JAR、
样例源文件和锁定配置的消费 job 中执行。该 job 不需要插件源码，也不从源码重新构建插件。
记录独立项目的 effective POM、命令和输出，证明没有借用同一 reactor 的解析或生命周期。
Invoker 可以自动驱动同一批样例，但“插件仓库里 Invoker 通过”不能替代这次独立消费验证。

每个独立项目提供自己的 README，用户能按文档指定候选插件版本，依次执行分析、dry-run、
裁剪和 native 构建；提供基线/实验/负向对照开关。不得要求用户运行内部单元测试来使用样例。
这两个项目用于可复现验收，另外仍须完成 7.4 所述的实际项目试用，二者不互相替代。

### 5.3 明确运行环境与结果

每个子 Maven 在同一进程中通过 fixture 内 Enforcer execution 断言预期 Java feature
版本和 Maven 版本；记录 `mvn --version`、`JAVA_HOME`、`java.home`、vendor、OS/arch。
同进程断言可以发现 PATH、Toolchain 或 IDEA 设置导致的运行 JDK 串用。

主要 native 测试在同一套 GraalVM 下运行 Maven 和 `native-image`，由 Maven JVM 的
`java.home` 定位工具并记录其绝对路径及 `native-image --version`。项目可显式覆盖工具路径，
覆盖时记录两者差异，不保留个人机器路径回退。不同 JDK 的分离运行属于可选补充场景，
不得用它替代同套 GraalVM 的主要验收，也不得在子进程中悄悄升级待验证的 Maven JDK。

结果只有 `PASS`、`FAIL`、`NOT_RUN` 三类；门禁所需场景必须为 `PASS`。
在未选择 native profile 时可以不执行原生测试并明确记为 `NOT_RUN`；选择了 native profile
却缺工具、超时或没有执行，整个 job 失败。

所有外部命令检查退出码、限制时间并保留完整日志：普通 fixture 构建 5 分钟、单个 native
构建 20 分钟、应用启动 60 秒、HTTP 请求 10 秒为初始上限。超时终止进程及子进程，
服务进程在验证结束后清理；不能无限 `readAllBytes()` 等待。

## 6. 验证矩阵

以下全部为计划执行项，目前不得标记通过。支持范围按列分别判断，不混淆 Maven JVM、
业务字节码版本与 Native Image 所用 JDK。

### 6.1 快速验证与低版本真实消费

| ID | Maven JVM | Maven | 输入/操作 | 必须证明 |
| --- | --- | --- | --- | --- |
| B25 | GraalVM 25 | 3.9.9 | `clean verify`，release 17，非 native | 生成有效插件、单元/普通集成测试通过 |
| U17/U21 | GraalVM 17 / 21 | 3.9.9 | 干净目录编译并运行核心单元测试 | 源码、测试及运行依赖未偷用高版本 API |
| R17/R21/R25 | GraalVM 17 / 21 / 25 | 3.9.9、3.10.0，各一组 | 消费 B25 同一个产物；执行两个 goal | 六个真实 Maven 进程均成功，产物哈希一致 |
| O17 | 普通 OpenJDK 17 | 3.9.9 | 消费 B25 产物；只执行分析/裁剪 | 补充证明不依赖 GraalVM 私有 API，不执行 native |
| X17 | GraalVM 17 | 3.9.9 | 读取预先由 JDK 25 生成的 17、21、25 class/JAR | 能提取成员和注解；不尝试加载高版本类 |
| MR | GraalVM 17 / 21 / 25 | 3.9.9 | 同一个 MR-JAR：带版本化的 class 条目，加上只在 base 提供的 `META-INF/native-image` 元数据，以及位于 `META-INF/versions/N/META-INF/native-image/` 的无效条目 | 按各自运行版本选择有效 class 条目；`META-INF/native-image` 只读 base 条目，无效的嵌套版本条目被忽略且不参与覆盖判断 |
| MAC | GraalVM 21 | 3.9.9 | macOS 两个 goal、目录/资源路径样例 | 常用本地开发平台冒烟通过 |

X17 使用 build job 的真实编译输出；不通过仅修改 class 文件 major number 伪造版本兼容。
准备一个类静态初始化会创建标记文件或抛异常的 fixture，断言扫描时标记从未出现。
此检查只证明没有初始化，不能单独证明没有加载。结合 X17 成功读取 JVM 17 无法加载的
高版本 class、缺少父类/依赖的样例，以及扫描实现中无业务类加载调用的审查，共同验证
扫描只依赖字节文件。

MR 的结果允许随运行 JDK 变化。选择规则就是 JVM 自己的规则（`JarFile.versionedStream()`）；
本次不新增任何自定义选择或覆盖模式。按 JAR 规范，`META-INF/` 下的资源不可版本化，因此
`META-INF/versions/N/META-INF/native-image/...` 既不会被扫描，也不能作为删除自持登记的覆盖证据。
文档说明当前选择规则，并在 native 测试中保持同版本或使用非 MR 输入。

### 6.2 Native Image 与真实框架

| ID | 插件所在 Maven JVM | 业务目标 | native 工具链 | 场景 |
| --- | --- | --- | --- | --- |
| N17 | 同右侧 GraalVM Java 17 | 17 | GraalVM CE Java 17 / 17.0.9+9.1 | 动态反射小应用：基线、裁剪、负向对照 |
| N25 | 同右侧 GraalVM Java 25 | 25 | GraalVM CE Java 25 / 25.0.2 | 同一类使用流程，验证高版本输入 |
| S17 | 同右侧 GraalVM Java 17 | 17 | GraalVM CE Java 17 / 17.0.9+9.1 | Spring Boot 3.5.16 AOT 与 REST 服务 |
| S21 | 同右侧 GraalVM Java 21 | 17 | GraalVM Java 21 / 21.0.12 | 相同 Spring 样例，验证常用较高版本环境 |

Java 17 工具链从最初的 22.3.3 修订为 CE 17.0.9+9.1；前者没有本机 arm64 分发，原因见
[validation/baseline.md](validation/baseline.md)。本机使用 Oracle GraalVM21.0.12，Linux CI 锁定
CE21.0.2；完整 vendor/平台/校验值见 toolchains.json，Linux CI 尚未运行。

Native jobs 先固定为 Linux x86_64 / Ubuntu 22.04。M0 必须验证上述固定工具分发可获取、
可在 runner 编译，并将 vendor、补丁版、下载地址与 SHA-256 写入测试工具链锁定文件。
其中 Java 17 的 GraalVM 发行版属于历史验证工具链，不代表推荐用户部署过期发行版。
如果 runner 或分发不可用，显式报告失败并修订这个固定组合，不以“工具不存在，跳过”放行。

Spring parent、Spring Boot Maven Plugin 固定 3.5.16，Native Build Tools 固定 0.10.6。
本阶段不测试全部历史 GraalVM，也不把 S17/S21 通过扩大为所有 Spring Boot 版本兼容。

## 7. 每类样例具体怎么验收

### 7.1 扫描与裁剪用例

样例包含普通类、record、零参数与同名重载、数组参数、可见/不可见注解、嵌套注解 class 引用、
直接方法/构造器调用、字段读写、反射调用点、重复类和 module-info。
用手工定义的期望清单检查名称、descriptor、标志、来源和引用；路径和必要的排序可归一化，
不能删去不一致的成员字段来让结果相等。

配置样例覆盖旧式 reflect-config 与统一 reachability-metadata 两种格式，并检查：

- `analyze` 前后源文件、输出资源、依赖 JAR 的内容哈希不变；报告内容符合样例。
- `dryRun` 只写报告、changelog、候选文件；输出目录和源文件哈希不变。
- 应用裁剪后，只改写允许的构建输出；源配置、生成源目录和依赖 JAR 哈希不变。
- JSON 中非 reflection 区段及未知数据保留；参数、condition、查询/调用权限按语义比较。
- 外部覆盖和已保留自持条目覆盖两条路径都测试；包含此前两个高优先级修复的反例和有效去重正例。
- 对同一源输入重复执行得到相同输出；从已裁剪输出再运行不会进一步丢失权限。
- 绝对路径、`..`、输出目录外逃及经符号链接回写源目录必须拒绝；候选文件写入也在边界内。
- 缺少显式配置、损坏 JSON/class、规则未命中或歧义时，错误可定位，输出元数据不被部分应用。

上述是实施期必须验证的契约，不是对当前实现已有能力的陈述；暴露安全缺陷时先修复再验收。

### 7.2 真正依赖配置的原生反射样例

保留可读、可手动执行的普通 Maven 应用，不再只在测试方法内动态拼整套项目。
类名、方法名及必要参数由程序运行时命令行/外部输入提供，避免编译器提前推断反射目标。
应用确实调用声明的构造器、字段与方法；必须有不需要的登记可供安全删除或显式收窄。

对每个工具链，从独立干净目录执行以下三组：

| 组别 | 配置 | 预期 |
| --- | --- | --- |
| A 基线 | 原始可工作的配置，不执行插件裁剪 | 原生编译成功，运行返回约定业务结果 |
| B 实验 | 执行插件裁剪后的实际消费配置 | 原生编译成功，运行结果与 A 相同，确实发生预期配置变更 |
| C 负向对照 | 在 B 的独立副本里移除一个运行时必需登记 | 同一动态操作必须失败，并明确是缺失登记相关失败 |

C 不得在待测源码、生成目录或缓存里残留另一份同等登记。只删除 JAR 内的一份而有效配置
并集仍提供该权限，不能算有效负向对照。编译失败只有明确由缺失登记引起时才有证明力；
网络错误、C 编译器错误或随便一个非零退出码不算。

如果 C 仍成功，说明样例不足以证明配置有效性，必须修正样例或排查重复登记；不能用 A/B
都成功替代 C。C 是测试隔离副本中的操作，产品不得提供自动删除必需登记的默认行为。

### 7.3 Spring Boot AOT 使用样例

建立 `examples/spring-boot-native`，至少包含 REST endpoint、Jackson DTO/record、
一个需要显式 RuntimeHints 的运行时动态操作，以及手写自持配置。
生成配置由真正的 `spring-boot:process-aot` 产生，不能手工伪造目录充当 AOT 证据。

验收流程：

1. 使用锁定 parent/plugin，保存 effective POM 和实际 goal 顺序；执行 AOT 并清点生成文件。
2. 不启用本插件裁剪构建原生基线，启动后用真实 HTTP 请求验证 JSON 入参、出参和动态操作。
3. 启用 `analyze`/`dryRun`，报告须包含自持、依赖、AOT 生成来源；确认源码和生成源未改写。
4. 在干净副本执行裁剪、打包及原生构建；在 Native Image 实际消费路径中检查配置，而非只看 boot JAR。
5. 以相同请求检查与基线相同的状态码、JSON 和动态行为；服务启动和健康检查也有超时。
6. 加入一个与真实 AOT 登记完全重叠、由应用自持的配置作为去重正例，证明插件确实执行并产生可解释变更。
7. 在隔离副本删除必要的动态操作登记、清除所有副本，再执行相同动态请求，形成缺失配置负向对照。

普通 Spring AOT 配置无需裁剪时允许输出零变更，不把“必须减少百分之多少”当产品门槛。
专门安排的去重正例必须发生变化，否则不能认为插件已正确接入生命周期。

### 7.4 手动使用演练

先由维护者从干净 checkout 按样例 README 操作一次，并保留使用记录：

- 终端 GraalVM 17、IDEA 项目 SDK / Maven Runner GraalVM 17 各一次；确认实际运行 JDK。
- 只照文档执行：准备依赖、分析、阅读报告、dry-run、规则配置、裁剪、原生构建、HTTP 验证。
- 分别记录原有问题、用户必须理解的信息、报错是否可行动、源码是否恢复、退出路径。
- 至少一个实际准备采用插件的项目完成试用，保留可复现的脱敏记录；可由维护者自己维护的项目开始。
  仅有新建玩具 fixture 的结果不能声称完成实际项目试用。

没有实际项目时，该项保持 `NOT_RUN`，不得编造“生产验证”或宣称已有普遍优化收益。
不自动访问、改写或向外提交其他项目；有现成授权和可用项目时再执行。

## 8. CI 与证据格式

建议工作流按以下依赖执行：

`构建 B25 / 生成高版本 fixtures → 同产物 R/X/MR 测试 → native 测试 → 汇总验收`

核心单元测试 U17/U21 可独立并行。PR 必跑 B25、U17/U21、六个 R job、O17、X17、MR、MAC，
以及 N17/N25；S17/S21 在 Spring 样例、生命周期/配置发现、报告或裁剪逻辑变更时也是 PR 必跑。
发布候选必须全部跑完 S17/S21，不以最近另一提交的结果代替。工作流改为 `verify` 门禁，
并确保 required checks 不因路径过滤而缺失或无限等待。

native job 明确列为必需验证时，不能 `continue-on-error`；可缓存依赖，不能复用上次的
应用原生二进制、AOT 输出或插件产物。所有执行都关联当前提交和插件 JAR 哈希。

每个 case 输出 `environment.json`、`result.json` 和原始日志。结果最低字段：

```json
{
  "caseId": "R17-maven-3.9.9",
  "status": "PASS|FAIL|NOT_RUN",
  "commit": "<完整提交哈希>",
  "pluginVersion": "<本次项目版本>",
  "pluginSha256": "<实际消费的 JAR 哈希>",
  "mavenVersion": "3.9.9",
  "mavenJavaFeature": 17,
  "javaHome": "<子 Maven JVM 内读取>",
  "nativeImageVersion": null,
  "exitCodes": {},
  "assertions": {},
  "reason": null
}
```

native case 还记录 GraalVM 分发哈希、实际命令与输入 classpath/元数据清单、输出 binary 哈希、
A/B/C 各组结果。日志归档到 `target/compat` 并由 CI 上传；失败与超时同样上传。

补充记录配置条目/显式成员数、构建时间、二进制大小和环境信息。数据用于理解效果，
本阶段不设未经测量的性能阈值，也不承诺裁剪一定使二进制更小。

## 9. 分阶段实施与退出条件

| 阶段 | 修改范围/交付物 | 完成条件 |
| --- | --- | --- |
| M0 固定基线 | 工具链锁定文件、现状记录、Java 17 runner、样例源代码与期望清单 | 固定版本可解析/启动，当前失败与跳过均如实记录 |
| M1 修正验证基础 | `src/it`、Invoker、隔离仓库、版本过滤、超时与证据输出；移除旧自安装测试 | 在尚未迁移的插件上，真实 JDK 17 消费测试稳定失败，不能假绿 |
| M2 迁移解析与编译 | ASM scanner、POM、Java 17 测试 API、单元 fixtures | B25、U17/U21、X17 通过，产物为 Java 17，现有语义回归无退化 |
| M3 验证用户消费 | 独立消费项目、跨 job 同产物传递、两个 Maven 版本、MR、路径/错误处理测试 | 六个 R job、MR、MAC 全过；脱离插件源码消费同产物，哈希与运行 JDK 证据齐全 |
| M4 原生因果验证 | 动态反射样例、明确的 native profile、A/B/C | N17/N25 全过，C 确因缺少配置失败 |
| M5 Spring 集成 | 只读生成来源、生命周期示例、Boot 样例与 HTTP 验证 | S17/S21 全过，AOT 生成、配置被消费和预期去重有直接证据 |
| M6 使用与交付 | 实际项目试用、IDEA 演练、README、兼容表和候选版本记录 | 用户按文档完成流程；所有支持声明与对应证据一致 |

按这些阶段拆成可审查的变更，不把解析器重写、测试框架和 Spring 集成揉成一次不可定位的大改。
M1 的低版本失败用于保存迁移前的负向证据；如分步合并，尚未实现的兼容门禁须明确标为
待启用，不能用永久忽略失败的配置维持绿色。M2/M3 后启用必需门禁，发布前不允许保留豁免。
任何阶段暴露裁剪安全问题，先停用该场景的应用裁剪，保留只读分析用于定位；修正后重跑
受影响用例及后续验收。最终兼容版本使用新的发布版本号，不覆盖已发布的 `1.0.0`。

实施阶段的建议命令入口（以下接口需先实现，目前不可直接当作现有脚本运行）：

```text
mvn -B -ntp clean verify
  # 核心单元测试 + 非 native Invoker；主要在 GraalVM 17 验证

scripts/verify-compatibility.sh --artifact <jar> --pom <pom> \
  --maven-home <maven> --java-home <jdk> --case <case-id>
  # 同产物消费；不重新构建插件

mvn -B -ntp verify -Pnative-it -DnativeImageExecutable=<绝对路径>
  # 必须运行 native A/B/C，缺工具即失败

mvn -B -ntp verify -Pspring-native-it -DnativeImageExecutable=<绝对路径>
  # 必须运行 Spring AOT + native + HTTP 对照
```

`verify-compatibility.sh` 自己不能把构建机的 JDK 路径继续传给低版本子 Maven。
普通验证入口不激活 `central`，不会签名、发布或修改远端。

## 10. 最终验收清单

本机已通过项如下；AC-11 的远程 CI、AC-12 的 IDEA 与 AC-14 的发布仍待执行。

- [ ] AC-01：GraalVM 17 下两个 goal 与原生构建流程真实执行；O17 补充验证无 GraalVM 的分析/裁剪。
- [ ] AC-02：六个 R job 消费 JDK 25 构建的同一 JAR，版本和 SHA-256 有证据。
- [ ] AC-03：JDK 17 编译/核心测试成功；插件 class 为 61，依赖有效路径兼容 17。
- [ ] AC-04：JDK 17 读取真实 21/25 class 的结果正确，扫描不加载业务类。
- [ ] AC-05：MR-JAR 选择、来源、重载/注解/record 和反射调用点符合样例语义。
- [ ] AC-06：分析只读、dry-run、写入边界、原文件保留及失败不应用配置都通过。
- [ ] AC-07：既有 public/declared 和构造器查询权限回归测试继续通过。
- [ ] AC-08：原生 A/B/C 通过；负向失败能证明样例确实依赖配置。
- [ ] AC-09：Spring AOT 的真实生成配置进入报告，并核实其实际原生消费路径。
- [ ] AC-10：Spring 基线与裁剪后 HTTP/动态操作等价，预期去重生效，负向对照有效。
- [ ] AC-11：所需 native 场景没有因工具缺失静默跳过；完整命令、退出码和日志归档。
- [ ] AC-12：维护者终端/IDEA 演练及至少一个实际项目试用记录可复查。
- [ ] AC-13：README 记录运行 JDK、构建 JDK、业务目标和 native 工具链的区别及支持边界。
- [ ] AC-14：所有测试结果对应候选提交；发布使用新版本号，声明不超出已验证范围。
- [ ] AC-15：两个独立 Maven 项目在插件 checkout 之外，仅消费插件坐标完成验证；不依赖插件源码、测试类或 reactor。

AC-01～AC-11、AC-15 是技术验收；AC-12～AC-14 是“可供普通用户采用”的交付验收。
未做实际项目试用时可以报告技术进度，但不能把全部工作标记完成。

## 11. 官方参考

以下链接用于核对设计依据；实现依赖和测试工具链仍固定具体版本，不能随文档最新版漂移。

- [Maven 插件类加载](https://maven.apache.org/guides/mini/guide-maven-classloading.html)：插件类加载隔离不等于独立 JVM。
- [Maven Toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html)：工具链可独立于运行 Maven 的 JDK。
- [Maven Compiler --release](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html)：限制目标字节码和 API。
- [Plugin descriptor 配置](https://maven.apache.org/plugin-tools/maven-plugin-plugin/descriptor-mojo.html)：最低 Java/Maven 声明及 Maven 版本间的处理差异。
- [Maven Invoker 用法](https://maven.apache.org/plugins/maven-invoker-plugin/usage.html)：暂存本次产物、隔离仓库、过滤项目版本与校验脚本。
- [ASM 版本记录](https://asm.ow2.io/versions.html)：解析器与 class 格式支持范围。
- [Class-File API / JEP 484](https://openjdk.org/jeps/484)：与 JDK 格式同步演进的设计目标、版本历史与分析能力边界。
- [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)：当前固定样例 3.5.16、Java 17～25 和 native 构建要求。
- [Spring Boot 3.5 Maven AOT](https://docs.spring.io/spring-boot/3.5/maven-plugin/aot.html)：`process-aot`、native profile 与原始 classpath。
- [IDEA Maven Runner](https://www.jetbrains.com/help/idea/maven-runner.html)：执行 Maven goal 的 JRE 设置。

# IDEA 手动演练（WAIVED）

**状态：维护者决定 waive（2026-10-15）。本项不再有待办动作；下面的步骤保留，供日后需要时执行。**

理由，按重要性排序：

1. 本插件**没有 IDE 集成**：它不是 IDEA 插件，也不调用任何 IDE API，只通过 Maven 坐标被消费。
   因此这一子项防护的是 IDE 自身的 Maven 管道，而不是插件的代码路径。
2. 它真正要防的机制风险是“**执行 Maven 的 JVM ≠ 构建产物的 JVM**”。该机制已由 CI 的
   `plain OpenJDK 17` job（用 JDK 17 的 Maven 消费 JDK 25 构建的产物）与 detached-consumer 套件覆盖。
3. 执行条件在本机不成立：没有屏幕录制与辅助功能授权（`screencapture` 报 could not create image from
   display、`osascript` 请求进程名超时无输出），`cua-driver` 与 `open-computer-use` 二进制缺失，
   自动化通道不可用；为此安装的 IntelliJ IDEA CE 2025.2.5 又被 macOS 的 Gatekeeper 隔离标记挡住
   （`xattr -dr com.apple.quarantine` 返回 Operation not permitted），JVM 从未启动，无法无人值守执行。

因此 AC-12 保持 PARTIAL（其文本要求 IDE 走查）：真实项目试用（java-pi）已通过，IDE 子项为
**明说的、已决定的缺口**，不再作为待办。

## 准备

1. 将 `examples/plain-native` 复制到插件 checkout 外，使用该副本创建 IDEA 项目。
2. 将本次候选插件 JAR 及内嵌 POM 暂存到专用 Maven 仓库，核对验证报告中的 SHA-256。
   1.1.0 尚未发布；不要把无法解析坐标当作 Java 17 不兼容，也不要回退到旧 1.0.0。
3. 使用已验证的 GraalVM 17 和 Maven 3.9.9；保留 Maven 输出和 environment.json 作为证据。

## 项目与 Maven 设置

1. Project Structure → Project SDK 选择 GraalVM 17；独立样例的业务 release 保持 17。
2. Settings → Build Tools → Maven，Maven home 选择固定的 Maven 3.9.9，
   Local repository 选择上述专用仓库。
3. Maven → Runner 的 JRE 选择同一 GraalVM 17。记录 Importing 使用的 JDK；
   Project SDK、Importer 和 Maven Runner 是不同设置，不能用其中一个推断另一个。
4. 从 Maven 工具窗口运行，不用 IDE 内嵌终端来替代 Maven Runner。

## 执行与断言

运行以下 Maven goals（专用仓库也可作为运行参数显式传入）：

```text
clean package -Dnative.metadata.version=1.1.0 -Dnative.metadata.dryRun=true -Drequired.java.version=[17,18) -Drequired.maven.version=[3.9.9,3.9.10)
```

确认 `target/native-metadata/environment.json` 的 javaFeature 为 17、mavenVersion 为
3.9.9、pluginImplementationVersion 为 1.1.0；候选配置和报告存在，src 及 target/classes
中的源配置副本未被 dry-run 改写。关闭 dry-run 再运行相同流程，确认 changes 与候选配置
一致，实际 target/classes 配置改变，src 保持不变。

Native Image 的 A/B/C 可再通过该样例 README 的 verify.sh 执行；这一部分终端验证已有
证据，不能据此代替上面的 IDE Runner 证据。真实 java-pi 保持其 JDK 25 要求，不能为了
IDE 演练降低它的编译版本。

完成后记录 IDEA 版本、平台、Project SDK、Importer JDK、Runner JRE、Maven home、
插件哈希、操作输出及实际 environment.json。只有收到真实执行记录，AC-12 的 IDEA
子项才可标 PASS。

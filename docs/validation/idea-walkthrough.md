# IDEA 手动演练（NOT_RUN）

本机的 `/Applications`、`~/Applications`、JetBrains Toolbox apps 目录未找到 IntelliJ
IDEA，当前工具也不提供桌面 UI 操作。因此这里只提供步骤，不能把终端测试记作 IDEA 验证。
该未完成项属于 AC-12；java-pi 实际项目试用已单独通过。

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

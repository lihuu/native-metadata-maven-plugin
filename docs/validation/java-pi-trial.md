# java-pi 真实项目试用

2026-10-15，用户指定项目 `java-pi`，源码提交
`a4381a2c58712c88ec92c29ce7b874f9e0ca1b1b`（2.14.1）。通过 `git archive HEAD`
复制到临时目录；原仓库保持干净，不修改其 JDK 25 要求、源码或配置。

## 环境与范围

- macOS arm64，GraalVM CE 25.0.2，Maven 3.9.9。
- 插件候选版本 1.1.0，Java 25 构建、release 17；与兼容矩阵使用同一冻结制品。
- SHA-256：`3881f13ceab5f47fbe200b0b66f4cc168986bdcbf8e75b0b18084d9e0d22ff18`。
- 独立 Maven 仓库，只向临时副本的 licode-core 增加 `metadata-dogfood` profile。
- 两个 goal 绑定 process-classes，位于现有 javac2 插桩之后；Native Image 使用 core JAR。
- 覆盖 CLI/core 和实际 Native Image MCP 业务；未运行 GUI、完整阶段验收或真实模型服务。

## 流程与结果

| 检查 | 结果 |
| --- | --- |
| 原始配置 Native Image 构建 | PASS |
| 原生基线 MCP 闭环 | PASS |
| analyze / dry-run | PASS，102 个自持类型全部解析成功 |
| 实际 apply / Native Image 构建 | PASS，0 条变更 |
| 应用后原生 MCP 闭环 | PASS，与基线相同业务断言通过 |
| 源码文件哈希和反射权限 | PASS，均保持不变 |
| 插件实际运行环境 | 进程内 environment.json：Java25、GraalVM Community、Maven3.9.9、插件1.1.0 |

分析统计：301 个字段、83 个构造器、340 个方法；0 个无法解析的自持类型，
0 个不支持的自持条目。零变更是有效的保守结果，不能据此宣称减少了登记或原生体积。
二进制构建存在非确定因素，本轮没有进行可归因的性能或体积收益评测。

本地业务夹具见 [java-pi-native-smoke.py](java-pi-native-smoke.py)。它使用临时 HOME、
固定假密钥、本地 HTTP/SSE 模型和 Python stdio MCP peer，断言工具发现、调用、
嵌入资源、structuredContent 进入下一轮模型请求，并获得 `NATIVE_MCP_DOGFOOD_OK`。
基线和应用后运行相同脚本，无用户凭据或外部模型调用。

## 复现入口

先从上述提交建立干净副本，向专用 Maven 仓库 install-file 安装候选 JAR 和其内嵌 POM，
校验 SHA-256，然后在 licode-core 临时 profile 中配置：

```xml
<plugin>
  <groupId>io.github.lihuu</groupId>
  <artifactId>native-metadata-maven-plugin</artifactId>
  <version>1.1.0</version>
  <executions><execution>
    <id>metadata-dogfood</id><phase>process-classes</phase>
    <goals><goal>analyze</goal><goal>prune</goal></goals>
  </execution></executions>
</plugin>
```

```sh
# JAVA_HOME=GraalVM25；以下命令只针对独立副本和隔离仓库
cp "$PLUGIN_CHECKOUT/docs/validation/java-pi-native-smoke.py" "$EVIDENCE/"
mvn -B -ntp -Dmaven.repo.local="$REPO" -pl licode-cli -am -Pnative -DskipTests package
cp target/li "$EVIDENCE/baseline-li"
python3 "$EVIDENCE/java-pi-native-smoke.py" "$EVIDENCE/baseline-li" baseline-smoke
mvn -B -ntp -Dmaven.repo.local="$REPO" -pl licode-core -Pmetadata-dogfood \
  -Dnative.metadata.dryRun=true process-classes
mvn -B -ntp -Dmaven.repo.local="$REPO" -pl licode-cli -am \
  -Pnative,metadata-dogfood -DskipTests package
python3 "$EVIDENCE/java-pi-native-smoke.py" target/li pruned-smoke
```

原始证据位于 `/private/tmp/native-metadata-pi-asm/java-pi-dogfood/`：
`identity.json`、`plugin.sha256`、`source-hashes-before.json`、`result.json`、
`baseline-build.log`、`final-dry-run.log`、`pruned-build.log`、`dry-run-report/`、
`applied-report/` 及两个 `*-smoke/` 目录。临时日志与二进制不纳入版本控制。

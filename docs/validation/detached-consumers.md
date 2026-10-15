# 插件源码目录外的独立消费复核

2026-10-15。仅复制验证脚本、样例和冻结 JAR/POM 到
`/private/tmp/native-metadata-pi-asm/detached-kit/`；该目录没有插件生产源码、测试类、
reactor 或编译输出。两个样例分别使用自己的 POM，只按 Maven 坐标消费插件。

被测插件：`io.github.lihuu:native-metadata-maven-plugin:1.1.0`，SHA-256：
`3881f13ceab5f47fbe200b0b66f4cc168986bdcbf8e75b0b18084d9e0d22ff18`。

| 项目 | 实际环境 | 结果与证据（相对临时证据根） |
| --- | --- | --- |
| plain-native | GraalVM17 / Maven3.9.9，工作目录包含空格 | PASS，`evidence/detached-kit/space-path/result.json`；两个 goal 执行，源文件哈希保持不变，实际消费配置改变，暂存 JAR 哈希相同 |
| spring-boot-native | GraalVM21 / Maven3.9.9，AOT + prune + native | 原生构建 PASS，三项真实 HTTP 请求与基线相同；`evidence/detached-kit/spring-native-21/native-http-result.json`、`consume.log` |
| Spring 通用校验器复验 | GraalVM21 / Maven3.9.9，复跑 AOT/prune，显式跳过重复 native 编译 | PASS，`evidence/detached-kit/postcondition-check/spring-native-21/result.json`；`configChanged=true`、暂存哈希相同、源码哈希不变 |

证据根为 `/private/tmp/native-metadata-pi-asm/`。

Spring 首轮校验器在成功的原生构建之后误报：它只检查排序最前面的 reflect-config，
该项属于 AOT 生成目录，没有同路径源文件。现已修改为检查所有存在源文件对应关系的
配置，原始误报记录保留。随后单独复跑 AOT/prune 校验（`-DskipNativeBuild`）通过；
原生业务通过证据来自先前已经生成并实际运行的二进制，二者不混称为一次运行。

原生 A/B/C 因果对照另见 [migration-results.md](migration-results.md)。本文件补足两个
样例必须真正离开插件 checkout 的要求，不把“不同 POM”本身当作完整的独立消费证明。

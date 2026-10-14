package io.github.lihuu.nativemetadata.analyzer;

import io.github.lihuu.nativemetadata.scanner.DiscoveredMetadataSource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MarkdownReportFormatter {

    @NotNull
    public static String format(@NotNull AnalysisReport report) {
        ReportSummary summary = report.summary();
        List<String> lines = new ArrayList<>();
        lines.add("# 自持 Native Image 反射配置分析");
        lines.add("");
        lines.add("只读报告。普通字节码引用不代表 AOT 可达或运行时执行；待核对项不是可直接删除项。");
        lines.add("");
        lines.add("自持类型 **" + summary.ownedTypes() + "**，字节码已解析 **" + summary.resolvedTypes()
                + "**，被其他配置重复登记 **" + summary.protectedOverlaps() + "**。");
        lines.add("声明成员登记：字段 " + summary.registeredFields() + "、构造器 " + summary.registeredConstructors()
                + "、方法 " + summary.registeredMethods() + "。");
        if (summary.traceProvided()) {
            lines.add("有运行追踪元数据：下表待核对方法未在追踪中取得调用登记，且无已识别的 JSON 绑定线索。");
        } else {
            lines.add("未提供运行追踪：运行时使用情况未知，下表仅根据字节码绑定线索列出待核对方法。");
        }
        lines.add("");
        lines.add("## 自持类型");
        lines.add("");
        lines.add("| 类型 | 字段 | 构造器 | 方法 | 待核对方法 | 普通引用 | 注解引用 | 其他登记 |");
        lines.add("|---|---:|---:|---:|---:|---:|---:|---:|");

        for (ClassAnalysis entry : report.classes()) {
            lines.add("| `" + entry.name() + "` | "
                    + entry.registered().fields().size() + " | "
                    + entry.registered().constructors().size() + " | "
                    + entry.registered().methods().size() + " | "
                    + entry.reviewMethods().size() + " | "
                    + entry.ordinaryReferences().size() + " | "
                    + entry.annotationReferences().size() + " | "
                    + entry.alsoRegisteredBy().size() + " |");
        }

        lines.add("");
        lines.add("## 配置来源（全部只读）");
        lines.add("");
        lines.add("| 范围 | 登记数 | 来源 |");
        lines.add("|---|---:|---|");

        for (DiscoveredMetadataSource source : report.metadataSources()) {
            lines.add("| " + source.scope().name().toLowerCase() + " | " + source.reflectionEntries() + " | `" + source.source() + "` |");
            if (Boolean.FALSE.equals(source.matchesSource())) {
                lines.add("");
                lines.add("打包副本与源配置不同：`" + source.source() + "`，请确认构建产物是否对应当前源码。");
                lines.add("");
            }
        }

        List<ClassAnalysis> gaps = report.classes().stream()
                .filter(c -> !c.missingOwnedInvocations().isEmpty())
                .toList();
        if (!gaps.isEmpty()) {
            lines.add("");
            lines.add("## 追踪登记未被自持清单覆盖");
            lines.add("");
            lines.add("这些条目需要核对其他登记和静态分析结果，不直接判为 native 缺陷。");
            lines.add("");
            for (ClassAnalysis entry : gaps) {
                List<String> names = entry.missingOwnedInvocations().stream()
                        .map(m -> "`" + m.name() + "(" + String.join(", ", m.parameterTypes()) + ")`")
                        .toList();
                lines.add("- `" + entry.name() + "`: " + String.join(", ", names));
            }
        }

        List<String> notes = new ArrayList<>();
        for (ClassAnalysis entry : report.classes()) {
            for (String note : entry.notes()) {
                notes.add("- `" + entry.name() + "`: " + note);
            }
        }
        if (!notes.isEmpty()) {
            lines.add("");
            lines.add("## 分析限制");
            lines.add("");
            lines.addAll(notes);
        }

        lines.add("");
        lines.add("具体成员名、参数类型、注解、普通调用位置和反射 API 调用位置见 JSON 报告。");
        lines.add("不展开完整调用图、invokedynamic/方法句柄目标、Native Image Feature 注册或任意运行时类名；不估算字节节省量。");
        lines.add("下一步：核对 mapper/协议路径，手工修改自持配置，再通过 native 场景和同参数构建验证。");
        lines.add("");

        return String.join("\n", lines);
    }

    public static void writeMarkdown(@NotNull AnalysisReport report, @NotNull Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, format(report));
    }
}

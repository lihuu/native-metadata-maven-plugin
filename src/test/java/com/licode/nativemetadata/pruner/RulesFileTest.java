package com.licode.nativemetadata.pruner;

import com.licode.nativemetadata.model.MetadataDocument;
import com.licode.nativemetadata.model.ReflectionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesFileTest {

    @Test
    void narrowsMembersWithExplicitRule(@TempDir Path tempDir) throws IOException {
        Path rulesPath = tempDir.resolve("rules.json");
        String rulesJson = """
                [
                  {
                    "resource": "META-INF/native-image/test/reflect-config.json",
                    "type": "example.Message",
                    "replacement": {
                      "name": "example.Message",
                      "methods": [
                        {"name": "upper", "parameterTypes": []}
                      ]
                    }
                  }
                ]
                """;
        Files.writeString(rulesPath, rulesJson);

        List<PruningRule> rules = PruningRulesParser.parse(rulesPath);
        assertEquals(1, rules.size());

        ReflectionEntry broad = new ReflectionEntry(
                "example.Message", "name", null,
                true, false, true, false,
                true, false, false, false,
                false, false, false, false,
                false,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap()
        );

        PruningEngine pruner = new PruningEngine();
        PruningResult result = pruner.prune(
                List.of(new OwnedConfigEntry("META-INF/native-image/test/reflect-config.json", broad)),
                Collections.emptyList(),
                rules
        );

        assertEquals(1, result.changes().size());
        PruningChange change = result.changes().get(0);
        assertEquals(PruningAction.NARROWED, change.action());
        assertEquals("example.Message", change.typeName());

        List<ReflectionEntry> retained = result.retainedEntries("META-INF/native-image/test/reflect-config.json");
        assertEquals(1, retained.size());
        ReflectionEntry narrowed = retained.get(0);
        assertFalse(narrowed.allDeclaredMethods());
        assertEquals(1, narrowed.methods().size());
        assertEquals("upper", narrowed.methods().get(0).name());
    }

    @Test
    void failsWhenRuleMatchesNothing(@TempDir Path tempDir) throws IOException {
        Path rulesPath = tempDir.resolve("rules.json");
        String rulesJson = """
                [
                  {
                    "resource": "META-INF/native-image/test/reflect-config.json",
                    "type": "example.NonExistent",
                    "replacement": {
                      "name": "example.NonExistent"
                    }
                  }
                ]
                """;
        Files.writeString(rulesPath, rulesJson);
        List<PruningRule> rules = PruningRulesParser.parse(rulesPath);

        PruningEngine pruner = new PruningEngine();
        assertThrows(IllegalStateException.class, () -> pruner.prune(
                List.of(new OwnedConfigEntry("META-INF/native-image/test/reflect-config.json",
                        new ReflectionEntry("example.Other", "name", null, false, false, false, false,
                                false, false, false, false, false, false, false, false, false,
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyMap()))),
                Collections.emptyList(),
                rules
        ));
    }

    @Test
    void failsWhenRuleChangesType(@TempDir Path tempDir) throws IOException {
        Path rulesPath = tempDir.resolve("rules.json");
        String rulesJson = """
                [
                  {
                    "resource": "META-INF/native-image/test/reflect-config.json",
                    "type": "example.Foo",
                    "replacement": {
                      "name": "example.Bar"
                    }
                  }
                ]
                """;
        Files.writeString(rulesPath, rulesJson);
        List<PruningRule> rules = PruningRulesParser.parse(rulesPath);

        PruningEngine pruner = new PruningEngine();
        assertThrows(IllegalStateException.class, () -> pruner.prune(
                List.of(new OwnedConfigEntry("META-INF/native-image/test/reflect-config.json",
                        new ReflectionEntry("example.Foo", "name", null, false, false, false, false,
                                false, false, false, false, false, false, false, false, false,
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyMap()))),
                Collections.emptyList(),
                rules
        ));
    }

    @Test
    void writesChangelogJson(@TempDir Path tempDir) throws IOException {
        Path changelogPath = tempDir.resolve("changelog.json");
        List<PruningChange> changes = List.of(new PruningChange(
                "reflect-config.json", "example.Foo", null, PruningAction.DELETED,
                "Covered by external source", "All members covered"
        ));
        ChangelogWriter.write(changes, changelogPath);
        assertTrue(Files.isRegularFile(changelogPath));
        String json = Files.readString(changelogPath);
        assertTrue(json.contains("example.Foo"));
        assertTrue(json.contains("DELETED"));
    }
}

package io.github.lihuu.nativemetadata.model;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataDocumentTest {

    @Test
    void parsesReflectConfigArray() {
        String json = """
                [
                  {
                    "name": "com.example.Foo",
                    "allDeclaredFields": true,
                    "methods": [
                      {"name": "bar", "parameterTypes": ["int"]}
                    ]
                  }
                ]
                """;

        MetadataDocument doc = MetadataDocument.parse(json);
        assertEquals(MetadataDocument.Format.REFLECT_CONFIG, doc.format());
        assertEquals(1, doc.entries().size());

        ReflectionEntry entry = doc.entries().get(0);
        assertEquals("com.example.Foo", entry.name());
        assertTrue(entry.allDeclaredFields());
        assertFalse(entry.allDeclaredMethods());
        assertEquals(1, entry.methods().size());
        assertEquals("bar", entry.methods().get(0).name());
        assertEquals(List.of("int"), entry.methods().get(0).parameterTypes());

        String serialized = doc.serialize(doc.entries());
        MetadataDocument reparsed = MetadataDocument.parse(serialized);
        assertEquals(1, reparsed.entries().size());
        assertEquals("com.example.Foo", reparsed.entries().get(0).name());
    }

    @Test
    void parsesReachabilityMetadataPreservingOtherSections() {
        String json = """
                {
                  "reflection": [
                    {
                      "type": "com.example.Bar",
                      "allDeclaredConstructors": true
                    }
                  ],
                  "resources": [
                    {"glob": "META-INF/**"}
                  ]
                }
                """;

        MetadataDocument doc = MetadataDocument.parse(json);
        assertEquals(MetadataDocument.Format.REACHABILITY_METADATA, doc.format());
        assertEquals(1, doc.entries().size());
        assertEquals("com.example.Bar", doc.entries().get(0).name());

        String updated = doc.serialize(List.of());
        MetadataDocument reparsed = MetadataDocument.parse(updated);
        assertTrue(reparsed.entries().isEmpty());
        assertTrue(reparsed.rawRoot().containsKey("resources"));
    }

    @Test
    void unsupportedEntriesAreCountedAndRefuseToBeSerialized() {
        String json = """
                [
                  {"name": "com.example.Foo"},
                  {"note": "no name, not a reflection entry"},
                  "not even an object"
                ]
                """;

        MetadataDocument doc = MetadataDocument.parse(json);
        assertEquals(1, doc.entries().size());
        assertEquals(2, doc.unsupportedEntryCount(),
                "unparseable entries must be reported, not dropped silently");
        assertThrows(IllegalStateException.class, () -> doc.serialize(doc.entries()));
    }

    @Test
    void absentParameterTypesIsNotTheSameAsAnEmptyList() {
        String json = """
                [
                  {
                    "name": "com.example.Foo",
                    "methods": [
                      {"name": "everyOverload"},
                      {"name": "noArgs", "parameterTypes": []},
                      {"name": "oneArg", "parameterTypes": ["int"]}
                    ]
                  }
                ]
                """;

        MetadataDocument doc = MetadataDocument.parse(json);
        List<MethodConfig> methods = doc.entries().get(0).methods();
        assertFalse(methods.get(0).parameterTypesSpecified(),
                "a missing parameterTypes key means every overload");
        assertTrue(methods.get(1).parameterTypesSpecified(),
                "an explicit empty array means the zero-argument member");
        assertTrue(methods.get(2).parameterTypesSpecified());

        String serialized = doc.serialize(doc.entries());
        assertFalse(serialized.contains("\"parameterTypes\" : [ ]\n    }, {\n      \"name\" : \"everyOverload\""),
                "the absent form must not gain an empty array: " + serialized);
        MetadataDocument reparsed = MetadataDocument.parse(serialized);
        assertFalse(reparsed.entries().get(0).methods().get(0).parameterTypesSpecified(),
                "round-tripping must preserve 'every overload'");
        assertTrue(reparsed.entries().get(0).methods().get(1).parameterTypesSpecified());
        assertTrue(reparsed.entries().get(0).methods().get(1).parameterTypes().isEmpty());
    }

}

package com.licode.nativemetadata.model;

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
}

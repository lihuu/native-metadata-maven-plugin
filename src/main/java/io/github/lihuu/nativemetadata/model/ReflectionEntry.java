package io.github.lihuu.nativemetadata.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReflectionEntry(
        @NotNull String name,
        @NotNull String typeFieldName,
        @Nullable Map<String, Object> condition,
        boolean allDeclaredFields,
        boolean allPublicFields,
        boolean allDeclaredMethods,
        boolean allPublicMethods,
        boolean allDeclaredConstructors,
        boolean allPublicConstructors,
        boolean allDeclaredClasses,
        boolean allPublicClasses,
        boolean queryAllDeclaredMethods,
        boolean queryAllPublicMethods,
        boolean queryAllDeclaredConstructors,
        boolean queryAllPublicConstructors,
        boolean unsafeAllocated,
        @NotNull List<FieldConfig> fields,
        @NotNull List<MethodConfig> methods,
        @NotNull List<MethodConfig> queriedMethods,
        @NotNull Map<String, Object> extra
) {
    public boolean hasUnknownFields() {
        return !extra.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(typeFieldName, name);
        if (condition != null) {
            map.put("condition", condition);
        }
        if (allDeclaredFields) map.put("allDeclaredFields", true);
        if (allPublicFields) map.put("allPublicFields", true);
        if (allDeclaredMethods) map.put("allDeclaredMethods", true);
        if (allPublicMethods) map.put("allPublicMethods", true);
        if (allDeclaredConstructors) map.put("allDeclaredConstructors", true);
        if (allPublicConstructors) map.put("allPublicConstructors", true);
        if (allDeclaredClasses) map.put("allDeclaredClasses", true);
        if (allPublicClasses) map.put("allPublicClasses", true);
        if (queryAllDeclaredMethods) map.put("queryAllDeclaredMethods", true);
        if (queryAllPublicMethods) map.put("queryAllPublicMethods", true);
        if (queryAllDeclaredConstructors) map.put("queryAllDeclaredConstructors", true);
        if (queryAllPublicConstructors) map.put("queryAllPublicConstructors", true);
        if (unsafeAllocated) map.put("unsafeAllocated", true);

        if (!fields.isEmpty()) {
            List<Map<String, Object>> fieldMaps = new ArrayList<>();
            for (FieldConfig field : fields) {
                Map<String, Object> fMap = new LinkedHashMap<>(field.extra());
                fMap.put("name", field.name());
                fieldMaps.add(fMap);
            }
            map.put("fields", fieldMaps);
        }

        if (!methods.isEmpty()) {
            List<Map<String, Object>> methodMaps = new ArrayList<>();
            for (MethodConfig method : methods) {
                Map<String, Object> mMap = new LinkedHashMap<>(method.extra());
                mMap.put("name", method.name());
                mMap.put("parameterTypes", method.parameterTypes());
                methodMaps.add(mMap);
            }
            map.put("methods", methodMaps);
        }

        if (!queriedMethods.isEmpty()) {
            List<Map<String, Object>> queriedMaps = new ArrayList<>();
            for (MethodConfig method : queriedMethods) {
                Map<String, Object> qMap = new LinkedHashMap<>(method.extra());
                qMap.put("name", method.name());
                qMap.put("parameterTypes", method.parameterTypes());
                queriedMaps.add(qMap);
            }
            map.put("queriedMethods", queriedMaps);
        }

        map.putAll(extra);
        return map;
    }
}

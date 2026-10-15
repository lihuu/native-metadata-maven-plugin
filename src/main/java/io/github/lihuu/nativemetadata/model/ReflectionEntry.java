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
                methodMaps.add(methodToMap(method));
            }
            map.put("methods", methodMaps);
        }

        if (!queriedMethods.isEmpty()) {
            List<Map<String, Object>> queriedMaps = new ArrayList<>();
            for (MethodConfig method : queriedMethods) {
                queriedMaps.add(methodToMap(method));
            }
            map.put("queriedMethods", queriedMaps);
        }

        map.putAll(extra);
        return map;
    }

    /**
     * Member-level properties this model does not interpret (for example a per-field permission
     * flag) are carried through verbatim, and {@code parameterTypes} is only written when the input
     * actually named it, so that "every overload" does not become "the no-arg member".
     */
    private static Map<String, Object> methodToMap(MethodConfig method) {
        Map<String, Object> mMap = new LinkedHashMap<>(method.extra());
        mMap.put("name", method.name());
        if (method.parameterTypesSpecified()) {
            mMap.put("parameterTypes", method.parameterTypes());
        }
        return mMap;
    }
}

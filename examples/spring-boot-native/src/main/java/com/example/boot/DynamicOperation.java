package com.example.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Performs the dynamic operation. Both the target type and the method name arrive from the caller
 * (an HTTP query string), so no static analysis can resolve them.
 */
public final class DynamicOperation {

    private DynamicOperation() {
    }

    public static String run(String targetTypeName, String methodName, String argument) throws Exception {
        Class<?> type = Class.forName(targetTypeName);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        Method method = type.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(instance, argument));
    }
}

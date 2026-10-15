package com.example.plain;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Business operation of the sample: everything it reflects on - class, constructor parameter type,
 * field, method name and method parameter types - is supplied at run time.
 *
 * <p>Native Image cannot turn these strings into a compile-time reflection target, so the program
 * only works if the configuration handed to {@code native-image} actually registers what it uses.
 * That is what makes A/B/C (untouched / pruned / permission removed) meaningful.</p>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        String targetClass = input(args, 0, "plain.targetClass", "com.example.plain.Greeting");
        String methodName = input(args, 1, "plain.methodName", "describe");
        String message = input(args, 2, "plain.message", "hello");
        String suffix = input(args, 3, "plain.suffix", "world");
        String methodParameterType = input(args, 4, "plain.methodParameterType", "[Ljava.lang.String;");
        String ctorParameterType = input(args, 5, "plain.ctorParameterType", "java.lang.String");

        Class<?> clazz = Class.forName(targetClass);

        Class<?> ctorType = Class.forName(ctorParameterType);
        Constructor<?> constructor = clazz.getDeclaredConstructor(ctorType);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance(message);

        Field field = clazz.getDeclaredField("message");
        field.setAccessible(true);
        Object fieldBefore = field.get(instance);
        field.set(instance, message + "-via-field");
        Object fieldAfter = field.get(instance);

        Class<?> methodParameterClass = Class.forName(methodParameterType);
        Method method = clazz.getDeclaredMethod(methodName, methodParameterClass);
        method.setAccessible(true);
        Object methodResult = method.invoke(instance, (Object) new String[]{suffix});

        // A second, plain reference to the same type, so the operation is not reflection-only.
        Point point = Point.of(suffix);
        String pointLabel = point.describe(suffix);

        System.out.println("FIELD_BEFORE=" + fieldBefore);
        System.out.println("FIELD_AFTER=" + fieldAfter);
        System.out.println("METHOD_RESULT=" + methodResult);
        System.out.println("POINT=" + pointLabel);
        System.out.println("POINT_COMPONENTS=" + point.name() + "," + point.values().length);
        System.out.println("APP_OK");
    }

    private static String input(String[] args, int index, String property, String fallback) {
        if (index < args.length && !args[index].isBlank()) {
            return args[index];
        }
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    private App() {
    }
}

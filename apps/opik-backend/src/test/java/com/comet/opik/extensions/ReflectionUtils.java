package com.comet.opik.extensions;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public class ReflectionUtils {

    /**
     * Recursively searches for a field annotated with the given annotation.
     * If the field is not found in the test instance, and if the test instance is a nested class,
     * it attempts to retrieve the enclosing instance and search there.
     *
     * @param testInstance the test class instance
     * @param annotationClass the annotation to search for
     * @return A Map.Entry containing the found Field and the instance that owns it, or empty if not found.
     */
    public static Optional<Map.Entry<Field, Object>> findAnnotatedField(Object testInstance,
            Class<? extends Annotation> annotationClass) {
        Class<?> clazz = testInstance.getClass();

        while (clazz != null) { // Traverse nested class hierarchy
            Optional<Field> fieldOpt = Arrays.stream(clazz.getDeclaredFields())
                    .filter(f -> f.isAnnotationPresent(annotationClass))
                    .findFirst();

            if (fieldOpt.isPresent()) {
                return Optional.of(new AbstractMap.SimpleEntry<>(fieldOpt.get(), testInstance));
            }

            // Move to parent class and check enclosing instance
            Object outerInstance = getEnclosingInstance(testInstance);
            if (outerInstance != null) {
                return findAnnotatedField(outerInstance, annotationClass);
            }

            clazz = clazz.getEnclosingClass(); // Traverse further up if needed
        }
        return Optional.empty();
    }

    /**
     * Retrieves the enclosing instance of a non-static nested class.
     */
    private static Object getEnclosingInstance(Object instance) {
        try {
            Field outerField = instance.getClass().getDeclaredField("this$0");
            outerField.setAccessible(true);
            return outerField.get(instance);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}

package com.comet.opik.utils;

public class ClasspathChecker {

    public static boolean isClassPresent(String className) {
        try {
            // Try to load the class
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            // Class is not in the classpath
            return false;
        }
    }

}

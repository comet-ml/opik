package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Python source files to extract metric metadata.
 * This parser extracts class names, docstrings, and score method signatures
 * from Python metric implementations.
 */
@Slf4j
@UtilityClass
public class PythonMetricParser {

    // Pattern to match a class definition that extends BaseMetric (directly or indirectly)
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^class\\s+(\\w+)\\s*\\([^)]*(?:BaseMetric|Base\\w+)\\s*\\)",
            Pattern.MULTILINE);

    // Pattern to extract class-level docstring (triple-quoted string after class definition)
    private static final Pattern DOCSTRING_PATTERN = Pattern.compile(
            "class\\s+\\w+[^:]*:\\s*(?:\\n\\s*)?\"\"\"([\\s\\S]*?)\"\"\"",
            Pattern.MULTILINE);

    // Pattern to extract the score method signature
    private static final Pattern SCORE_METHOD_PATTERN = Pattern.compile(
            "def\\s+score\\s*\\(\\s*self\\s*,([^)]+)\\)",
            Pattern.MULTILINE);

    // Pattern to extract the __init__ method signature (can span multiple lines)
    private static final Pattern INIT_METHOD_PATTERN = Pattern.compile(
            "def\\s+__init__\\s*\\(\\s*self\\s*,([^)]+)\\)",
            Pattern.MULTILINE | Pattern.DOTALL);

    // Pattern to extract parameter with type annotation: name: type
    private static final Pattern PARAM_WITH_TYPE_PATTERN = Pattern.compile(
            "(\\w+)\\s*:\\s*([^,=]+?)(?:\\s*=|\\s*,|\\s*$)");

    // Pattern to extract parameter without type annotation
    private static final Pattern PARAM_WITHOUT_TYPE_PATTERN = Pattern.compile(
            "(\\w+)(?:\\s*=|\\s*,|\\s*$)");

    // Pattern to extract Args section from docstring
    private static final Pattern ARGS_SECTION_PATTERN = Pattern.compile(
            "Args:\\s*\\n([\\s\\S]*?)(?=\\n\\s*(?:Returns:|Raises:|Example:|$))",
            Pattern.MULTILINE);

    // Pattern to extract individual arg description from Args section
    private static final Pattern ARG_DESCRIPTION_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*(.+?)(?=\\n\\s*\\w+:|$)",
            Pattern.MULTILINE | Pattern.DOTALL);

    // Parameters to skip from __init__ (internal to BaseMetric)
    private static final List<String> INIT_PARAMS_TO_SKIP = List.of(
            "self", "name", "track", "project_name");

    /**
     * Parses a Python source file and extracts metric metadata.
     *
     * @param pythonCode The Python source code to parse
     * @param fileName   The name of the file (used for ID generation)
     * @return List of CommonMetric objects found in the file
     */
    public static List<CommonMetric> parse(@NonNull String pythonCode, @NonNull String fileName) {
        List<CommonMetric> metrics = new ArrayList<>();

        // Find all class definitions that extend BaseMetric
        Matcher classMatcher = CLASS_PATTERN.matcher(pythonCode);

        while (classMatcher.find()) {
            String className = classMatcher.group(1);

            // Skip base classes and internal classes
            if (className.startsWith("Base") || className.startsWith("_")) {
                continue;
            }

            try {
                CommonMetric metric = parseMetricClass(pythonCode, className, classMatcher.start());
                if (metric != null) {
                    metrics.add(metric);
                    log.debug("Parsed metric '{}' from file '{}'", className, fileName);
                }
            } catch (Exception e) {
                log.warn("Failed to parse metric class '{}' from file '{}': '{}'",
                        className, fileName, e.getMessage());
            }
        }

        return metrics;
    }

    /**
     * Parses a single metric class from the Python code.
     */
    private static CommonMetric parseMetricClass(String pythonCode, String className, int classStartIndex) {
        // Extract the class code block
        String classCode = extractClassCode(pythonCode, classStartIndex);
        if (classCode == null) {
            return null;
        }

        // Extract docstring
        String description = extractDocstring(classCode);

        // Extract score method parameters
        List<CommonMetric.ScoreParameter> parameters = extractScoreParameters(classCode, description);

        // Only include metrics that have a score method with parameters
        if (parameters.isEmpty()) {
            log.debug("Skipping metric '{}' - no score method parameters found", className);
            return null;
        }

        // Extract __init__ parameters (configuration options)
        List<CommonMetric.InitParameter> initParameters = extractInitParameters(classCode, description);

        // Generate ID from class name (lowercase with underscores)
        String id = toSnakeCase(className);

        return CommonMetric.builder()
                .id(id)
                .name(className)
                .description(cleanDescription(description))
                .code(classCode)
                .parameters(parameters)
                .initParameters(initParameters)
                .build();
    }

    /**
     * Extracts the full class code from the Python source.
     */
    private static String extractClassCode(String pythonCode, int startIndex) {
        // Find the end of the class by looking for the next class definition or end of file
        // Also handle dedented code (class ends when indentation returns to class level)
        String codeFromClass = pythonCode.substring(startIndex);

        // Find the next class definition at the same indentation level
        Pattern nextClassPattern = Pattern.compile("\\n(?=class\\s+\\w+)", Pattern.MULTILINE);
        Matcher nextClassMatcher = nextClassPattern.matcher(codeFromClass);

        int endIndex;
        if (nextClassMatcher.find()) {
            endIndex = nextClassMatcher.start();
        } else {
            endIndex = codeFromClass.length();
        }

        return codeFromClass.substring(0, endIndex).trim();
    }

    /**
     * Extracts the class-level docstring.
     */
    private static String extractDocstring(String classCode) {
        Matcher matcher = DOCSTRING_PATTERN.matcher(classCode);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * Extracts parameters from the score method signature and their descriptions from docstring.
     */
    private static List<CommonMetric.ScoreParameter> extractScoreParameters(String classCode, String docstring) {
        List<CommonMetric.ScoreParameter> parameters = new ArrayList<>();

        // Find the score method
        Matcher scoreMatcher = SCORE_METHOD_PATTERN.matcher(classCode);
        if (!scoreMatcher.find()) {
            return parameters;
        }

        String paramsString = scoreMatcher.group(1).trim();

        // Extract the score method's docstring for parameter descriptions
        String scoreDocstring = extractScoreMethodDocstring(classCode);
        var paramDescriptions = extractArgDescriptions(scoreDocstring.isEmpty() ? docstring : scoreDocstring);

        // Parse each parameter
        String[] paramParts = paramsString.split(",");
        for (String paramPart : paramParts) {
            paramPart = paramPart.trim();

            // Skip **kwargs and *args
            if (paramPart.startsWith("**") || paramPart.startsWith("*")) {
                continue;
            }

            // Skip 'self' parameter
            if (paramPart.equals("self")) {
                continue;
            }

            String paramName;
            String paramType = "Any";
            boolean required = true;

            // Check if parameter has a default value
            if (paramPart.contains("=")) {
                required = false;
                paramPart = paramPart.split("=")[0].trim();
            }

            // Try to extract type annotation
            Matcher typeMatcher = PARAM_WITH_TYPE_PATTERN.matcher(paramPart);
            if (typeMatcher.find()) {
                paramName = typeMatcher.group(1).trim();
                paramType = typeMatcher.group(2).trim();
            } else {
                Matcher noTypeMatcher = PARAM_WITHOUT_TYPE_PATTERN.matcher(paramPart);
                if (noTypeMatcher.find()) {
                    paramName = noTypeMatcher.group(1).trim();
                } else {
                    paramName = paramPart.trim();
                }
            }

            // Skip empty parameter names
            if (paramName.isEmpty()) {
                continue;
            }

            // Get description from docstring
            String paramDescription = paramDescriptions.getOrDefault(paramName, "");

            parameters.add(CommonMetric.ScoreParameter.builder()
                    .name(paramName)
                    .type(cleanType(paramType))
                    .description(paramDescription)
                    .required(required)
                    .build());
        }

        return parameters;
    }

    /**
     * Extracts parameters from the __init__ method signature.
     * These are configuration parameters that users can set when using the metric.
     */
    private static List<CommonMetric.InitParameter> extractInitParameters(String classCode, String docstring) {
        List<CommonMetric.InitParameter> parameters = new ArrayList<>();

        // Find the __init__ method
        Matcher initMatcher = INIT_METHOD_PATTERN.matcher(classCode);
        if (!initMatcher.find()) {
            return parameters;
        }

        String paramsString = initMatcher.group(1).trim();
        // Remove newlines and normalize whitespace for multi-line signatures
        paramsString = paramsString.replaceAll("\\s+", " ");

        // Extract argument descriptions from the class docstring
        var paramDescriptions = extractArgDescriptions(docstring);

        // Parse each parameter
        String[] paramParts = paramsString.split(",");
        for (String paramPart : paramParts) {
            paramPart = paramPart.trim();

            // Skip **kwargs and *args
            if (paramPart.startsWith("**") || paramPart.startsWith("*")) {
                continue;
            }

            String paramName;
            String paramType = "Any";
            String defaultValue = null;
            boolean required = true;

            // Check if parameter has a default value
            if (paramPart.contains("=")) {
                required = false;
                String[] parts = paramPart.split("=", 2);
                paramPart = parts[0].trim();
                defaultValue = parts[1].trim();
            }

            // Try to extract type annotation
            Matcher typeMatcher = PARAM_WITH_TYPE_PATTERN.matcher(paramPart);
            if (typeMatcher.find()) {
                paramName = typeMatcher.group(1).trim();
                paramType = typeMatcher.group(2).trim();
            } else {
                Matcher noTypeMatcher = PARAM_WITHOUT_TYPE_PATTERN.matcher(paramPart);
                if (noTypeMatcher.find()) {
                    paramName = noTypeMatcher.group(1).trim();
                } else {
                    paramName = paramPart.trim();
                }
            }

            // Skip empty parameter names and internal parameters
            if (paramName.isEmpty() || INIT_PARAMS_TO_SKIP.contains(paramName)) {
                continue;
            }

            // Get description from docstring
            String paramDescription = paramDescriptions.getOrDefault(paramName, "");

            parameters.add(CommonMetric.InitParameter.builder()
                    .name(paramName)
                    .type(cleanType(paramType))
                    .description(paramDescription)
                    .defaultValue(defaultValue)
                    .required(required)
                    .build());
        }

        return parameters;
    }

    /**
     * Extracts the docstring from the score method specifically.
     */
    private static String extractScoreMethodDocstring(String classCode) {
        // Find the score method and its docstring
        Pattern scoreDocPattern = Pattern.compile(
                "def\\s+score\\s*\\([^)]+\\)[^:]*:\\s*(?:\\n\\s*)?\"\"\"([\\s\\S]*?)\"\"\"",
                Pattern.MULTILINE);
        Matcher matcher = scoreDocPattern.matcher(classCode);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * Extracts argument descriptions from a docstring's Args section.
     */
    private static java.util.Map<String, String> extractArgDescriptions(String docstring) {
        java.util.Map<String, String> descriptions = new java.util.HashMap<>();

        Matcher argsSectionMatcher = ARGS_SECTION_PATTERN.matcher(docstring);
        if (!argsSectionMatcher.find()) {
            return descriptions;
        }

        String argsSection = argsSectionMatcher.group(1);

        // Parse each argument description
        Matcher argMatcher = ARG_DESCRIPTION_PATTERN.matcher(argsSection);
        while (argMatcher.find()) {
            String argName = argMatcher.group(1).trim();
            String argDesc = argMatcher.group(2).trim()
                    .replaceAll("\\s+", " "); // Normalize whitespace
            descriptions.put(argName, argDesc);
        }

        return descriptions;
    }

    /**
     * Converts a CamelCase class name to snake_case.
     */
    private static String toSnakeCase(String className) {
        return className
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .toLowerCase();
    }

    /**
     * Cleans up the type annotation string.
     */
    private static String cleanType(String type) {
        return type
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Cleans up the description, extracting just the first paragraph.
     */
    private static String cleanDescription(String description) {
        if (description == null || description.isEmpty()) {
            return "";
        }

        // Get the first paragraph (up to double newline or Args section)
        String[] parts = description.split("\\n\\s*\\n|\\nArgs:|\\nExample:|\\nReferences:");
        if (parts.length > 0) {
            return parts[0]
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        return description.replaceAll("\\s+", " ").trim();
    }

    /**
     * Validates if the parsed metric is suitable for online evaluation.
     * Metrics must have at least one required parameter for the score method.
     */
    public static boolean isValidForOnlineEvaluation(@NonNull CommonMetric metric) {
        return metric.parameters() != null &&
                !metric.parameters().isEmpty() &&
                metric.parameters().stream().anyMatch(CommonMetric.ScoreParameter::required);
    }
}

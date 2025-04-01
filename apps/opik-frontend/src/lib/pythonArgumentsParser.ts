interface MethodParameterInfo {
  name: string;
  type: string;
  optional: boolean;
}

// Enhanced regular expression to extract method parameters more comprehensively
export const createMethodRegex = (methodName: string) => {
  return new RegExp(
    // Start with 'def' followed by optional whitespace
    `def\\s+` +
      // Method name (passed as parameter)
      `${methodName}\\s*` +
      // Optional type hints for the entire method signature
      `(?:\\[\\w+\\])?` +
      // Opening parenthesis
      `\\(` +
      // Capture group for parameters (non-greedy)
      `([^)]*)` +
      // Closing parenthesis
      `\\)\\s*` +
      // Optional return type annotation
      `(?:->\\s*\\w+)?` +
      // Optional colon for method definition
      `:?`,
    // Flags: support multiline and dot all
    "ms",
  );
};

export const splitWithBracketPreservation = (input: string): string[] => {
  const result: string[] = [];
  let current = "";
  let bracketLevel = 0;

  for (const char of input) {
    if (char === "[") {
      bracketLevel++;
    } else if (char === "]") {
      bracketLevel--;
    }

    if (char === "," && bracketLevel === 0) {
      // Comma outside of brackets - split point
      result.push(current.trim());
      current = "";
    } else {
      current += char;
    }
  }

  // Add the last segment
  if (current.trim()) {
    result.push(current.trim());
  }

  return result;
};

export const parsePythonMethodParameters = (
  code: string,
  methodName: string,
): MethodParameterInfo[] => {
  const methodRegex = createMethodRegex(methodName);

  // Find the method definition
  const methodMatch = code.match(methodRegex);
  if (!methodMatch) {
    throw new Error(`Method ${methodName} not found`);
  }

  // Extract parameters string
  const paramsString = methodMatch[1].trim();

  // Parse parameters
  return (
    splitWithBracketPreservation(paramsString)
      .map((param) => param.trim())
      .filter((param) => param && param !== "self")
      // Exclude parameters starting with * or **
      .filter((param) => !param.startsWith("*"))
      .map((param) => {
        // Split into potential name, type, and default value parts
        const parts = param.split(":");

        // Handle name part with potential default value
        const namePart = parts[0].trim();

        // Determine if optional (has default value)
        let isOptional = namePart.includes("=");

        // Extract clean parameter name
        const name = isOptional ? namePart.split("=")[0].trim() : namePart;

        // Determine type
        let type = "any";
        if (parts.length > 1) {
          // If type part exists, extract it
          let typePart = parts[1].trim();

          isOptional = isOptional || typePart.includes("=");

          // Remove default value from type if exists
          typePart = typePart.split("=")[0].trim();

          type = typePart || "any";
        }

        return {
          name,
          type,
          optional: isOptional,
        };
      })
  );
};

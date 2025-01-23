interface MethodParameterInfo {
  name: string;
  type: string;
  optional: boolean;
}

export const parsePythonMethodParameters = (
  code: string,
  methodName: string,
): MethodParameterInfo[] => {
  // Regular expression to extract method parameters more flexibly
  const methodRegex = new RegExp(
    `def\\s+${methodName}\\s*\\(([^)]+)\\)\\s*:`,
    "s",
  );

  // Find the method definition
  const methodMatch = code.match(methodRegex);
  if (!methodMatch) {
    throw new Error(`Method ${methodName} not found`);
  }

  // Extract parameters string
  const paramsString = methodMatch[1].trim();

  // Parse parameters
  return (
    paramsString
      .split(",")
      .map((param) => param.trim())
      .filter((param) => param && param !== "self")
      // Exclude parameters starting with * or **
      .filter((param) => !param.startsWith("*"))
      .map((param) => {
        // Handle default values and type annotations
        const [namePart, typePart] = param.split(":").map((p) => p.trim());

        // Check for optional parameters (with default value)
        const isOptional = namePart.includes("=");

        // Extract parameter name (remove default value)
        const name = isOptional ? namePart.split("=")[0].trim() : namePart;

        // Extract type or use 'any' if not specified
        const type = typePart ? typePart.replace("=", "").trim() : "any";

        return {
          name,
          type,
          optional: isOptional,
        };
      })
  );
};

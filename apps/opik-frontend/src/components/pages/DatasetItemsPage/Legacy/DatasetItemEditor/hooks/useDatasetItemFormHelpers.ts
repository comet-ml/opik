import { z } from "zod";
import { FIELD_TYPE, DatasetField } from "./useDatasetItemData";

export const getFieldType = (
  value: unknown,
): { type: FIELD_TYPE; isJsonString: boolean; value: unknown } => {
  if (value === null || value === undefined) {
    return {
      type: FIELD_TYPE.SIMPLE,
      isJsonString: false,
      value,
    };
  }

  const valueType = typeof value;

  if (valueType === "object") {
    return {
      type: FIELD_TYPE.COMPLEX,
      isJsonString: false,
      value,
    };
  }

  // Try to parse any string as JSON
  if (valueType === "string") {
    try {
      const parsed = JSON.parse(value as string);
      const parsedType = typeof parsed;

      // If parsed result is object or array, it's complex
      if (parsedType === "object" && parsed !== null) {
        return {
          type: FIELD_TYPE.COMPLEX,
          isJsonString: true,
          value: parsed,
        };
      }

      // If parsed result is a primitive (number, boolean, null), keep as simple but use parsed value
      return {
        type: FIELD_TYPE.SIMPLE,
        isJsonString: true,
        value: parsed,
      };
    } catch {
      // Not valid JSON, treat as regular string
      return {
        type: FIELD_TYPE.SIMPLE,
        isJsonString: false,
        value,
      };
    }
  }

  return {
    type: FIELD_TYPE.SIMPLE,
    isJsonString: false,
    value,
  };
};

export const createDynamicSchema = (fields: DatasetField[]) => {
  const schemaShape: Record<string, z.ZodTypeAny> = {};

  fields.forEach((field) => {
    if (field.type === FIELD_TYPE.COMPLEX) {
      schemaShape[field.key] = z.string().refine(
        (val) => {
          const trimmed = val.trim();
          if (trimmed === "") return true;
          try {
            const parsed = JSON.parse(trimmed);
            return typeof parsed === "object" && parsed !== null;
          } catch {
            return false;
          }
        },
        { message: "Must be a valid JSON object or array" },
      );
    } else {
      schemaShape[field.key] = z.any();
    }
  });

  return z.object(schemaShape);
};

/**
 * Transforms form data before saving by parsing JSON strings back to objects/arrays.
 * This is necessary because COMPLEX fields are stringified for display in CodeMirror,
 * but need to be saved as proper JSON objects/arrays.
 * Only parses fields explicitly marked as COMPLEX to avoid accidentally converting
 * plain text strings that happen to be valid JSON.
 */
export const prepareFormDataForSave = (
  formData: Record<string, unknown>,
  fields: DatasetField[],
): Record<string, unknown> => {
  const result: Record<string, unknown> = {};

  // Create a map of field keys to their types for quick lookup
  const fieldTypeMap = new Map<string, FIELD_TYPE>();
  fields.forEach((field) => {
    fieldTypeMap.set(field.key, field.type);
  });

  for (const [key, value] of Object.entries(formData)) {
    const fieldType = fieldTypeMap.get(key);

    // Only parse JSON strings for fields explicitly marked as COMPLEX
    if (fieldType === FIELD_TYPE.COMPLEX && typeof value === "string") {
      try {
        const parsed = JSON.parse(value);
        // Only use parsed value if it's an object or array
        if (typeof parsed === "object" && parsed !== null) {
          result[key] = parsed;
          continue;
        }
      } catch {
        // Not valid JSON, keep as string
      }
    }
    result[key] = value;
  }

  return result;
};

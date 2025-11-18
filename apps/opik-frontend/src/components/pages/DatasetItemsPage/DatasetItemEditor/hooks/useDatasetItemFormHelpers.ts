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
      schemaShape[field.id] = z.string().refine(
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
      schemaShape[field.id] = z.any();
    }
  });

  return z.object(schemaShape);
};

import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnData } from "@/types/shared";
import { DatasetItem } from "@/types/datasets";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";

export enum FIELD_TYPE {
  SIMPLE = "simple",
  COMPLEX = "complex",
}

export type DatasetField = {
  id: string;
  label: string;
  value: unknown; // Parsed value (not the original string if it was JSON-encoded)
  type: FIELD_TYPE;
  isJsonString?: boolean; // True if value came from a JSON-encoded string
};

interface UseDatasetItemDataParams {
  datasetItemId?: string;
  columns: ColumnData<DatasetItem>[];
}

interface UseDatasetItemDataReturn {
  fields: DatasetField[];
  isPending: boolean;
  datasetItem: DatasetItem | undefined;
}

const getFieldType = (
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

export const useDatasetItemData = ({
  datasetItemId,
  columns,
}: UseDatasetItemDataParams): UseDatasetItemDataReturn => {
  const { data: datasetItem, isPending } = useDatasetItemById(
    { datasetItemId: datasetItemId || "" },
    {
      placeholderData: keepPreviousData,
      enabled: !!datasetItemId,
    },
  );

  const fields = useMemo(() => {
    if (!datasetItemId) {
      return columns
        .filter(
          (c) =>
            c.id !== "tags" &&
            c.id !== "created_at" &&
            c.id !== "last_updated_at" &&
            c.id !== "created_by",
        )
        .map((column) => ({
          id: column.id,
          label: column.label,
          value: "",
          type: FIELD_TYPE.SIMPLE,
          isJsonString: false,
        }));
    }

    if (!datasetItem?.data) {
      return [];
    }

    const dataObject = datasetItem.data as Record<string, unknown>;

    const mappedFields: (DatasetField | null)[] = columns.map((column) => {
      const fieldName = column.id;
      const rawValue = dataObject[fieldName];

      if (rawValue === undefined) {
        return null;
      }

      const { type, isJsonString, value } = getFieldType(rawValue);

      return {
        id: fieldName,
        label: column.label,
        value,
        type,
        isJsonString,
      };
    });

    return mappedFields.filter(
      (field): field is DatasetField => field !== null,
    );
  }, [datasetItem, columns, datasetItemId]);

  return {
    fields,
    isPending: !!datasetItemId && isPending,
    datasetItem,
  };
};

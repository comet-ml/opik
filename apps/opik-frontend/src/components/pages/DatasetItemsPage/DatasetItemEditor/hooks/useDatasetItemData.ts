import { useMemo } from "react";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import { getFieldType } from "./useDatasetItemFormHelpers";

export enum FIELD_TYPE {
  SIMPLE = "simple",
  COMPLEX = "complex",
}

export type DatasetField = {
  key: string;
  value: unknown; // Parsed value (not the original string if it was JSON-encoded)
  type: FIELD_TYPE;
  isJsonString?: boolean; // True if value came from a JSON-encoded string
};

interface UseDatasetItemDataParams {
  datasetItemId?: string;
  columns: DatasetItemColumn[];
}

interface UseDatasetItemDataReturn {
  fields: DatasetField[];
  isPending: boolean;
  datasetItem: DatasetItem | undefined;
}

export const useDatasetItemData = ({
  datasetItemId,
  columns,
}: UseDatasetItemDataParams): UseDatasetItemDataReturn => {
  const { data: datasetItem, isPending } = useDatasetItemById(
    { datasetItemId: datasetItemId || "" },
    {
      enabled: !!datasetItemId,
    },
  );

  const fields = useMemo(() => {
    if (!datasetItemId) {
      return columns.map((column) => ({
        key: column.name,
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
      const fieldName = column.name;
      const rawValue = dataObject[fieldName];

      if (rawValue === undefined) {
        return null;
      }

      const { type, isJsonString, value } = getFieldType(rawValue);

      return {
        key: fieldName,
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

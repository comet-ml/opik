import { useMemo } from "react";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import useDatasetItemById from "@/api/datasets/useDatasetItemById";
import {
  useIsDraftMode,
  useAddedDatasetItemById,
  useEditedDatasetItemById,
} from "@/store/DatasetDraftStore";
import { getFieldType } from "./useDatasetItemFormHelpers";

export enum FIELD_TYPE {
  SIMPLE = "simple",
  COMPLEX = "complex",
}

export type DatasetField = {
  key: string;
  value: unknown;
  type: FIELD_TYPE;
  isJsonString?: boolean;
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
  const isDraftMode = useIsDraftMode();
  const draftItem = useAddedDatasetItemById(datasetItemId);
  const editedFields = useEditedDatasetItemById(datasetItemId);

  const { data: apiDatasetItem, isPending: apiIsPending } = useDatasetItemById(
    { datasetItemId: datasetItemId || "" },
    {
      enabled: !!datasetItemId && !draftItem,
    },
  );

  const datasetItem = useMemo(() => {
    if (draftItem) return draftItem;

    if (!apiDatasetItem) return undefined;

    if (!isDraftMode) return apiDatasetItem;

    if (!editedFields) return apiDatasetItem;

    return {
      ...apiDatasetItem,
      ...editedFields,
    };
  }, [draftItem, apiDatasetItem, isDraftMode, editedFields]);

  const isPending = !datasetItemId || draftItem ? false : apiIsPending;

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
    isPending,
    datasetItem,
  };
};

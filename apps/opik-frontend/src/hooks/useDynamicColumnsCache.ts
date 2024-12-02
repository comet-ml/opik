import useLocalStorageState from "use-local-storage-state";
import { useEffect } from "react";
import difference from "lodash/difference";
import { OnChangeFn } from "@/types/shared";
import union from "lodash/union";

type UseDynamicColumnsCacheParams = {
  dynamicColumnsKey: string;
  dynamicColumnsIds: string[];
  setSelectedColumns: OnChangeFn<string[]>;
};

export const useDynamicColumnsCache = ({
  dynamicColumnsKey,
  dynamicColumnsIds,
  setSelectedColumns,
}: UseDynamicColumnsCacheParams) => {
  const [, setPresentedDynamicColumns] = useLocalStorageState<string[]>(
    dynamicColumnsKey,
    {
      defaultValue: [],
    },
  );

  useEffect(() => {
    setPresentedDynamicColumns((cols) => {
      const newDynamicColumns = difference(dynamicColumnsIds, cols);

      if (newDynamicColumns.length > 0) {
        setSelectedColumns((selected) => union(selected, newDynamicColumns));
      }

      return union(dynamicColumnsIds, cols);
    });
  }, [dynamicColumnsIds, setPresentedDynamicColumns, setSelectedColumns]);
};

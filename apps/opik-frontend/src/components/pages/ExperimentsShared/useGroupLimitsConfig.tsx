import React, { useCallback, useState } from "react";
import { Row } from "@tanstack/react-table";
import {
  DEFAULT_EXPERIMENTS_PER_GROUP,
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import { Button } from "@/components/ui/button";

export const useGroupLimitsConfig = () => {
  const [groupLimit, setGroupLimit] = useState<Record<string, number>>({});

  const renderMoreRow = useCallback((row: Row<GroupedExperiment>) => {
    return (
      <tr key={row.id} className="border-b">
        <td colSpan={row.getAllCells().length} className="px-2 py-1">
          <Button
            variant="link"
            className="w-full"
            onClick={() => {
              setGroupLimit((state) => {
                return {
                  ...state,
                  [row.original.dataset_id]:
                    (state[row.original.dataset_id] ||
                      DEFAULT_EXPERIMENTS_PER_GROUP) +
                    DEFAULT_EXPERIMENTS_PER_GROUP,
                };
              });
            }}
          >
            Load {DEFAULT_EXPERIMENTS_PER_GROUP} more experiments
          </Button>
        </td>
      </tr>
    );
  }, []);

  return {
    groupLimit,
    renderMoreRow,
  };
};

import React, { useEffect, useMemo } from "react";

import { DatasetItem } from "@/types/datasets";
import { Button } from "@/components/ui/button";
import { Braces } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import uniq from "lodash/uniq";
import ExperimentDatasetItems from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/ExperimentDatasetItems";
import useLocalStorageState from "use-local-storage-state";
import difference from "lodash/difference";
import union from "lodash/union";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

interface ExperimentDatasetProps {
  data: DatasetItem["data"] | undefined;
}

const SELECTED_DATA_SET_ITEM_KEYS =
  "experiment-sidebar-selected-dataset-item-keys";
const DYNAMIC_DATA_SET_ITEM_KEYS =
  "experiment-sidebar-dynamic-dataset-item-keys";

const ExperimentDataset = ({ data }: ExperimentDatasetProps) => {
  const [selectedKeys, setSelectedKeys] = useLocalStorageState<string[] | null>(
    SELECTED_DATA_SET_ITEM_KEYS,
    {
      defaultValue: null,
    },
  );

  const [, setDynamicKeys] = useLocalStorageState<string[]>(
    DYNAMIC_DATA_SET_ITEM_KEYS,
    {
      defaultValue: [],
    },
  );

  const dataKeys = useMemo(() => {
    const keys = Object.keys(data || {});

    return uniq(keys);
  }, [data]);

  const handleCheckChange = (key: string) => {
    setSelectedKeys((selectedKeys) => {
      if (selectedKeys?.includes(key)) {
        return selectedKeys.filter((sk) => sk !== key);
      }

      return [...(selectedKeys || []), key];
    });
  };

  useEffect(() => {
    setDynamicKeys((storedDynamicKeys) => {
      const newDynamicKeys = difference(dataKeys, storedDynamicKeys);

      if (newDynamicKeys.length > 0) {
        setSelectedKeys((sKeys) => union(sKeys, newDynamicKeys));
      }

      return union(newDynamicKeys, storedDynamicKeys);
    });
  }, [dataKeys, setSelectedKeys, setDynamicKeys]);

  return (
    <div className="min-w-72 max-w-full flex-1 pr-6 pt-4">
      <div className="flex items-center justify-between pb-4">
        <div className="flex items-center gap-1">
          <h4 className="comet-body-accented">Dataset item</h4>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_dataset_item]}
          />
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="sm" variant="outline">
              <Braces className="mr-2 size-4" />
              Keys
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {dataKeys.map((key) => (
              <DropdownMenuCustomCheckboxItem
                key={key}
                checked={selectedKeys?.includes(key)}
                onSelect={(event) => event.preventDefault()}
                onCheckedChange={() => handleCheckChange(key)}
              >
                {key}
              </DropdownMenuCustomCheckboxItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <ExperimentDatasetItems data={data} selectedKeys={selectedKeys || []} />
    </div>
  );
};

export default ExperimentDataset;

import React, { useCallback, useEffect, useMemo, useState } from "react";

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
import NavigationTag from "@/components/shared/NavigationTag/NavigationTag";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

const COLLAPSE_KEYS_BUTTON_WIDTH = 340;

interface ExperimentDatasetProps {
  data: DatasetItem["data"] | undefined;
  datasetItemId: string | undefined;
}

const SELECTED_DATA_SET_ITEM_KEYS =
  "experiment-sidebar-selected-dataset-item-keys";
const DYNAMIC_DATA_SET_ITEM_KEYS =
  "experiment-sidebar-dynamic-dataset-item-keys";

const ExperimentDataset = ({ data, datasetItemId }: ExperimentDatasetProps) => {
  const datasetId = useDatasetIdFromCompareExperimentsURL();
  const [isCollapsed, setIsCollapsed] = useState(false);

  const handleResize = useCallback((node: HTMLDivElement) => {
    setIsCollapsed(node.offsetWidth < COLLAPSE_KEYS_BUTTON_WIDTH);
  }, []);

  const { ref: containerRef } =
    useObserveResizeNode<HTMLDivElement>(handleResize);

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
    <div ref={containerRef} className="min-w-72 max-w-full flex-1 pr-6 pt-4">
      <div className="flex items-center justify-between gap-2 pb-4">
        <div className="flex min-w-0 shrink items-center gap-1">
          <h4 className="comet-body-accented truncate">Dataset item</h4>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_dataset_item]}
          />
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {datasetItemId && (
            <NavigationTag
              id={datasetId}
              name="View item"
              resource={RESOURCE_TYPE.datasetItem}
              search={{ row: datasetItemId }}
              tooltipContent="View this item in the dataset"
              className="h-8"
            />
          )}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              {isCollapsed ? (
                <TooltipWrapper content="Keys">
                  <Button size="icon-sm" variant="outline">
                    <Braces className="size-4" />
                  </Button>
                </TooltipWrapper>
              ) : (
                <Button size="sm" variant="outline">
                  <Braces className="mr-2 size-4" />
                  Keys
                </Button>
              )}
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
      </div>

      <ExperimentDatasetItems data={data} selectedKeys={selectedKeys || []} />
    </div>
  );
};

export default ExperimentDataset;

import React, { useEffect, useMemo } from "react";

import { extractImageUrls } from "@/lib/images";
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
import ExperimentDatasetItems from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/OutputTab/ExperimentDatasetItems";
import useLocalStorageState from "use-local-storage-state";

interface ExperimentDatasetProps {
  data: DatasetItem["data"] | undefined;
}

const isASubsetB = (arrA: string[], arrB: string[]) => {
  return arrA.every((a) => arrB.includes(a));
};

const IMAGES_KEY = "images";
const SELECTED_DATA_SET_ITEM_KEYS =
  "experiment-sidebar-selected-dataset-item-keys";

const ExperimentDataset = ({ data }: ExperimentDatasetProps) => {
  const [selectedKeys, setSelectedKeys] = useLocalStorageState<string[] | null>(
    SELECTED_DATA_SET_ITEM_KEYS,
    {
      defaultValue: null,
    },
  );

  const imagesUrls = useMemo(() => extractImageUrls(data), [data]);
  const hasImages = imagesUrls.length > 0;

  const dataKeys = useMemo(() => {
    const keys = Object.keys(data || {});

    if (hasImages && !keys.includes(IMAGES_KEY)) {
      keys.push(IMAGES_KEY);
    }

    return uniq(keys);
  }, [data, hasImages]);

  const handleCheckChange = (key: string) => {
    setSelectedKeys((selectedKeys) => {
      if (selectedKeys?.includes(key)) {
        return selectedKeys.filter((sk) => sk !== key);
      }

      return [...(selectedKeys || []), key];
    });
  };

  useEffect(() => {
    setSelectedKeys((sk) => {
      if (sk === null || !isASubsetB(sk, dataKeys)) {
        return dataKeys;
      }

      return sk;
    });
  }, [dataKeys, setSelectedKeys]);

  return (
    <div className="min-w-72 max-w-full flex-1 pr-6 pt-6">
      <div className="flex items-start justify-between pb-4">
        <p className="comet-body-accented">Dataset item</p>
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

      <ExperimentDatasetItems
        hasImages={hasImages}
        imagesUrls={imagesUrls}
        data={data}
        selectedKeys={selectedKeys || []}
        imagesKey={IMAGES_KEY}
      />
    </div>
  );
};

export default ExperimentDataset;

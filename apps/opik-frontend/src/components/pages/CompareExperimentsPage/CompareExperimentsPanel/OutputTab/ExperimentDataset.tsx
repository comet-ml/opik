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
import difference from "lodash/difference";
import union from "lodash/union";

interface ExperimentDatasetProps {
  data: DatasetItem["data"] | undefined;
}

const IMAGES_KEY = "images";

const comparatorToMakeImagesFirst = (a: string, b: string) => {
  return Number(b === IMAGES_KEY) - Number(a === IMAGES_KEY);
};

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

  const imagesUrls = useMemo(() => extractImageUrls(data), [data]);
  const hasImages = imagesUrls.length > 0;

  const dataKeys = useMemo(() => {
    const keys = Object.keys(data || {});

    if (hasImages && !keys.includes(IMAGES_KEY)) {
      keys.push(IMAGES_KEY);
    }

    return uniq(keys).sort(comparatorToMakeImagesFirst);
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
    setDynamicKeys((storedDynamicKeys) => {
      const newDynamicKeys = difference(dataKeys, storedDynamicKeys);

      if (newDynamicKeys.length > 0) {
        setSelectedKeys((sKeys) => union(sKeys, newDynamicKeys));
      }

      return union(newDynamicKeys, storedDynamicKeys);
    });
  }, [dataKeys, setSelectedKeys, setDynamicKeys]);

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

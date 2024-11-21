import React, { useEffect, useMemo, useState } from "react";

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

interface ExperimentDatasetProps {
  data: DatasetItem["data"] | undefined;
}

const IMAGES_KEY = "images";

const ExperimentDataset = ({ data }: ExperimentDatasetProps) => {
  const [selectedKeys, setSelectedKeys] = useState<string[] | null>(null);

  const imagesUrls = useMemo(() => extractImageUrls(data), [data]);
  const hasImages = imagesUrls.length > 0;

  const dataKeys = useMemo(() => {
    const keys = Object.keys(data || {});

    if (hasImages && !keys.includes(IMAGES_KEY)) {
      keys.push(IMAGES_KEY);
    }

    return uniq(keys);
  }, [data, hasImages]);

  useEffect(() => {
    setSelectedKeys((sk) => {
      if (sk === null) {
        return dataKeys;
      }

      return sk;
    });
  }, [dataKeys]);

  //
  return (
    <div className="h-full min-w-72 flex-1 overflow-auto pr-6 pt-6">
      <div className="max-w-full">
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
                  onCheckedChange={() =>
                    setSelectedKeys((selectedKeys) => {
                      if (selectedKeys?.includes(key)) {
                        return selectedKeys.filter((sk) => sk !== key);
                      }

                      return [...(selectedKeys || []), key];
                    })
                  }
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
        />
      </div>
    </div>
  );
};

export default ExperimentDataset;

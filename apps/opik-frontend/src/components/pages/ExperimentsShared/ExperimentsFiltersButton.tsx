import React, { useCallback, useMemo, useState } from "react";
import { Filter as FilterIcon } from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { keepPreviousData } from "@tanstack/react-query";
import { DropdownOption } from "@/types/shared";
import useAppStore from "@/store/AppStore";

const DEFAULT_LOADED_DATASET_ITEMS = 25;

type ExperimentsFiltersButtonProps = {
  datasetId: string;
  onChangeDatasetId: (id: string) => void;
};

const ExperimentsFiltersButton: React.FunctionComponent<
  ExperimentsFiltersButtonProps
> = ({ datasetId, onChangeDatasetId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [open, setOpen] = useState(false);

  const clearHandler = useCallback(() => {
    onChangeDatasetId("");
  }, [onChangeDatasetId]);

  const { data, isLoading } = useDatasetsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_DATASET_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((dataset) => ({
      value: dataset.id,
      label: dataset.name,
    }));
  }, [data?.content]);

  return (
    <Popover onOpenChange={setOpen} open={open}>
      <PopoverTrigger asChild>
        <Button variant="secondary" size="sm">
          <FilterIcon className="mr-2 size-3.5" />
          Filters ({datasetId === "" ? 0 : 1})
        </Button>
      </PopoverTrigger>
      <PopoverContent className="min-w-[540px] px-8 py-6" align="start">
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between pb-1">
            <span className="comet-title-s">Filters</span>
            <Button
              variant="ghost"
              size="sm"
              className="-mr-2.5"
              onClick={clearHandler}
            >
              Clear all
            </Button>
          </div>
          <Separator />
          <div className="-mr-1 max-h-[50vh] overflow-y-auto overflow-x-hidden py-4">
            <table className="table-auto">
              <tbody>
                <tr>
                  <td className="comet-body-s p-1">Where</td>
                  <td className="p-1">
                    <div className="comet-body-s flex h-10 w-28 cursor-default items-center rounded-md border px-4">
                      Dataset
                    </div>
                  </td>
                  <td className="p-1">
                    <div className="comet-body-s flex h-10 w-20 cursor-default items-center rounded-md border px-4">
                      =
                    </div>
                  </td>
                  <td className="p-1">
                    <LoadableSelectBox
                      options={options}
                      value={datasetId}
                      placeholder="Select a dataset"
                      onChange={onChangeDatasetId}
                      onLoadMore={
                        total > DEFAULT_LOADED_DATASET_ITEMS && !isLoadedMore
                          ? loadMoreHandler
                          : undefined
                      }
                      buttonClassName="w-[320px]"
                      isLoading={isLoading}
                      optionsCount={DEFAULT_LOADED_DATASET_ITEMS}
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default ExperimentsFiltersButton;

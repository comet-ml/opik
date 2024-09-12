import React, { useState } from "react";
import { JsonParam, useQueryParam } from "use-query-params";
import isArray from "lodash/isArray";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { keepPreviousData } from "@tanstack/react-query";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";

const DEFAULT_SIZE = 10;

type AddExperimentToCompareDialogProps = {
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddExperimentToCompareDialog: React.FunctionComponent<
  AddExperimentToCompareDialogProps
> = ({ datasetId, open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);

  const [experimentsIds = [], setExperimentsIds] = useQueryParam(
    "experiments",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const { data, isPending } = useExperimentsList(
    {
      workspaceName,
      datasetId,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const experiments = data?.content ?? [];
  const total = data?.total ?? 0;

  const renderListItems = () => {
    if (isPending) {
      return <Loader />;
    }

    if (experiments.length === 0) {
      return (
        <div className="comet-body-s flex h-32 items-center justify-center text-muted-slate">
          No search results
        </div>
      );
    }

    return experiments.map((e) => {
      const exist = experimentsIds.includes(e.id);
      return (
        <div
          key={e.id}
          className={cn("rounded-sm px-4 py-2.5 flex", {
            "cursor-pointer hover:bg-muted": !exist,
            "cursor-default opacity-50": exist,
          })}
          onClick={() => {
            if (!exist) {
              setOpen(false);
              setExperimentsIds((state: string[]) =>
                isArray(state) ? [...state, e.id] : [e.id],
              );
            }
          }}
        >
          <div className="comet-body-s-accented truncate">{e.name}</div>
        </div>
      );
    });
  };

  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-lg sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>Add to compare</DialogTitle>
          </DialogHeader>
          <div className="w-full overflow-hidden">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search by name"
            ></SearchInput>
            <div className="my-4 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
              {renderListItems()}
            </div>
            {total > DEFAULT_SIZE && (
              <div className="pt-4">
                <DataTablePagination
                  page={page}
                  pageChange={setPage}
                  size={size}
                  sizeChange={setSize}
                  total={total}
                ></DataTablePagination>
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
};

export default AddExperimentToCompareDialog;

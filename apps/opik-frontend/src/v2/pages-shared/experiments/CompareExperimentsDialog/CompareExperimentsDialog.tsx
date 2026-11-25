import React, { useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import Loader from "@/shared/Loader/Loader";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/shared/SearchInput/SearchInput";
import { formatExperimentSubtext } from "./formatExperimentSubtext";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import { Label } from "@/ui/label";
import { createFilter } from "@/lib/filters";

const DEFAULT_SIZE = 5;

export type CompareExperimentsDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetId: string;
  experimentsIds: string[];
  onCompare: (experimentsIds: string[]) => void;
};

const CompareExperimentsDialog: React.FC<CompareExperimentsDialogProps> = ({
  open,
  setOpen,
  datasetId,
  experimentsIds,
  onCompare,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_SIZE);

  const [selectedExperimentsIds, setSelectedExperimentsIds] =
    useState<string[]>(experimentsIds);

  const { data, isPending } = useExperimentsList(
    {
      workspaceName,
      projectId: activeProjectId ?? undefined,
      filters: [
        createFilter({
          id: "dataset_id",
          field: "dataset_id",
          operator: "=",
          value: datasetId,
        }),
      ],
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
      enabled: open,
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
      const checked = selectedExperimentsIds.includes(e.id);
      return (
        <label
          key={e.id}
          className="flex cursor-pointer flex-col gap-0.5 rounded p-1"
        >
          <div className="flex h-5 items-center gap-1.5">
            <Checkbox
              checked={checked}
              onCheckedChange={() =>
                setSelectedExperimentsIds((ids) => {
                  return checked
                    ? ids.filter((id) => id !== e.id)
                    : [...ids, e.id];
                })
              }
              aria-label="Select experiment"
              className="mx-0.5"
            />
            <span className="comet-body-s truncate">{e.name}</span>
          </div>
          <div className="comet-body-xs break-words pl-[26px] text-light-slate">
            {formatExperimentSubtext(e)}
          </div>
        </label>
      );
    });
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[640px]">
        <DialogHeader>
          <DialogTitle>Add to compare</DialogTitle>
          <DialogDescription className="text-muted-slate">
            Compare experiments to understand how changes impact performance.
          </DialogDescription>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <div className="flex flex-col gap-1">
            <Label
              htmlFor="compare-experiments-search"
              className="px-0.5 pb-0.5"
            >
              Search experiments
            </Label>
            <SearchInput
              id="compare-experiments-search"
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search"
            ></SearchInput>
          </div>
          <div className="mt-3 flex max-h-[400px] min-h-36 max-w-full flex-col justify-stretch overflow-y-auto">
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
        <DialogFooter className="border-t pt-4">
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={selectedExperimentsIds.length === 0}
              onClick={() => onCompare(selectedExperimentsIds)}
            >
              Compare {selectedExperimentsIds.length}{" "}
              {selectedExperimentsIds.length === 1
                ? "experiment"
                : "experiments"}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default CompareExperimentsDialog;

import React, { useState } from "react";
import { JsonParam, useQueryParam } from "use-query-params";
import { MessageCircleWarning } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import useAppStore from "@/store/AppStore";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";

const DEFAULT_SIZE = 5;

export type CompareExperimentsDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const CompareExperimentsDialog: React.FC<CompareExperimentsDialogProps> = ({
  open,
  setOpen,
}) => {
  const datasetId = useDatasetIdFromCompareExperimentsURL();
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

  const [selectedExperimentsIds, setSelectedExperimentsIds] =
    useState<string[]>(experimentsIds);

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
      const checked = selectedExperimentsIds.includes(e.id);
      return (
        <label
          key={e.id}
          className="flex cursor-pointer flex-col gap-0.5 py-2.5 pl-3 pr-4"
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
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
                className="mt-0.5"
              />
              <span className="comet-body-s-accented truncate">{e.name}</span>
            </div>
            <div className="comet-body-s truncate pl-6 text-light-slate">
              {formatDate(e.created_at)}
            </div>
          </div>
        </label>
      );
    });
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Compare experiments</DialogTitle>
        </DialogHeader>
        <div className="w-full overflow-hidden">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search by name"
          ></SearchInput>
          <Alert className="mt-4">
            <MessageCircleWarning />
            <AlertDescription>
              Only experiments using the same dataset as the baseline can be
              added to compare.
            </AlertDescription>
          </Alert>
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
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={selectedExperimentsIds.length === 0}
              onClick={() => setExperimentsIds(selectedExperimentsIds)}
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

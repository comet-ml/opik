import React, { useState } from "react";
import { JsonParam, useQueryParam } from "use-query-params";
import isArray from "lodash/isArray";
import { FlaskConical, MessageCircleWarning } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import Loader from "@/components/shared/Loader/Loader";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import useAppStore from "@/store/AppStore";
import { Alert, AlertDescription } from "@/components/ui/alert";

const DEFAULT_SIZE = 5;

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
          className={cn(
            "rounded-sm px-4 py-2.5 flex flex-col",
            exist ? "cursor-default" : "cursor-pointer hover:bg-muted",
          )}
          onClick={() => {
            if (!exist) {
              setOpen(false);
              setExperimentsIds((state: string[]) =>
                isArray(state) ? [...state, e.id] : [e.id],
              );
            }
          }}
        >
          <div className="flex flex-col gap-0.5">
            <div className="flex items-center gap-2">
              <FlaskConical
                className={cn(
                  "size-4 shrink-0",
                  exist ? "text-muted-gray" : "text-muted-slate",
                )}
              />
              <span
                className={cn(
                  "comet-body-s-accented truncate w-full",
                  exist && "text-muted-gray",
                )}
              >
                {e.name}
              </span>
            </div>
            <div
              className={cn(
                "comet-body-s pl-6",
                exist ? "text-muted-gray" : "text-light-slate",
              )}
            >
              {formatDate(e.created_at)}
            </div>
          </div>
        </div>
      );
    });
  };

  return (
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
          <Alert className="mt-4">
            <MessageCircleWarning className="size-4" />
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
      </DialogContent>
    </Dialog>
  );
};

export default AddExperimentToCompareDialog;

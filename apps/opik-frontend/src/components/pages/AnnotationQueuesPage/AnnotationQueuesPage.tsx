import React, { useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { MoreHorizontal, Plus, Users } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Separator } from "@/components/ui/separator";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import PageLayout from "@/components/layout/PageLayout/PageLayout";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import { cn } from "@/lib/utils";

import { AnnotationQueue } from "@/types/annotationQueues";
import useAnnotationQueuesList from "@/api/annotationQueues/useAnnotationQueuesList";
import useAnnotationQueueDeleteMutation from "@/api/annotationQueues/useAnnotationQueueDeleteMutation";
import CreateAnnotationQueueDialog from "./CreateAnnotationQueueDialog";

const AnnotationQueuesPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search, setSearch] = useState<string>("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [openDialog, setOpenDialog] = useState(false);

  const { data, isPending } = useAnnotationQueuesList(
    {
      workspaceName,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const deleteAnnotationQueueMutation = useAnnotationQueueDeleteMutation();

  const queues: AnnotationQueue[] = useMemo(() => data?.content ?? [], [data]);
  const total = data?.total ?? 0;
  const noData = !search;
  const noDataText = noData
    ? "There are no annotation queues yet"
    : "No search results";

  const columns = useMemo(() => {
    const handleDelete = (queue: AnnotationQueue) => {
      deleteAnnotationQueueMutation.mutate({
        queueId: queue.id,
        workspaceName,
      });
    };

    return [
      {
        accessorKey: "name",
        header: "Name",
        cell: ({ row }: { row: { original: AnnotationQueue } }) => {
          const queue = row.original;
          return (
            <div className="flex items-center gap-3">
              <Users className="size-4 text-muted-foreground shrink-0" />
              <div className="flex flex-col gap-1 truncate">
                <div className="font-medium truncate">{queue.name}</div>
                {queue.description && (
                  <div className="text-sm text-muted-foreground truncate">
                    {queue.description}
                  </div>
                )}
              </div>
            </div>
          );
        },
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: ({ row }: { row: { original: AnnotationQueue } }) => {
          const queue = row.original;
          return (
            <div
              className={cn(
                "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
                {
                  "bg-green-100 text-green-800": queue.status === "active",
                  "bg-gray-100 text-gray-800": queue.status === "completed",
                  "bg-yellow-100 text-yellow-800": queue.status === "paused",
                }
              )}
            >
              {queue.status}
            </div>
          );
        },
      },
      {
        accessorKey: "progress",
        header: "Progress",
        cell: ({ row }: { row: { original: AnnotationQueue } }) => {
          const queue = row.original;
          const progress = queue.total_items > 0 
            ? Math.round((queue.completed_items / queue.total_items) * 100)
            : 0;
          
          return (
            <div className="flex items-center gap-2">
              <div className="text-sm">
                {queue.completed_items}/{queue.total_items}
              </div>
              <div className="w-16 bg-gray-200 rounded-full h-2">
                <div
                  className="bg-blue-600 h-2 rounded-full"
                  style={{ width: `${progress}%` }}
                />
              </div>
              <div className="text-sm text-muted-foreground">
                {progress}%
              </div>
            </div>
          );
        },
      },
      {
        accessorKey: "assigned_smes",
        header: "SMEs",
        cell: ({ row }: { row: { original: AnnotationQueue } }) => {
          const queue = row.original;
          return (
            <div className="text-sm">
              {queue.assigned_smes?.length ?? 0}
            </div>
          );
        },
      },
      {
        accessorKey: "updated_at",
        header: "Updated",
        cell: ({ row }: { row: { original: AnnotationQueue } }) => {
          const queue = row.original;
          return (
            <div className="text-sm text-muted-foreground">
              {formatDate(queue.updated_at)}
            </div>
          );
        },
      },
      {
        id: "actions",
        cell: ({ row }: { row: { original: AnnotationQueue } }) => {
          const queue = row.original;
          return (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" className="h-8 w-8 p-0">
                  <MoreHorizontal className="h-4 w-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  onClick={() => {
                    if (queue.share_url) {
                      navigator.clipboard.writeText(queue.share_url);
                    }
                  }}
                >
                  Copy share link
                </DropdownMenuItem>
                <DropdownMenuItem>Add items</DropdownMenuItem>
                <DropdownMenuItem>View details</DropdownMenuItem>
                <Separator />
                <DropdownMenuItem
                  className="text-destructive"
                  onClick={() => handleDelete(queue)}
                >
                  Delete
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          );
        },
      },
    ];
  }, [workspaceName, deleteAnnotationQueueMutation]);

  if (isPending) {
    return (
      <PageLayout title="Human Feedback">
        <Loader />
      </PageLayout>
    );
  }

  return (
    <PageLayout title="Human Feedback">
      <div className="flex items-center justify-between py-6">
        <div className="flex items-center gap-4">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search queues..."
            className="w-[320px]"
          />
        </div>
        <Button onClick={() => setOpenDialog(true)}>
          <Plus className="mr-2 h-4 w-4" />
          Create queue
        </Button>
      </div>

      <div className="flex flex-col gap-4">
        <DataTable
          columns={columns}
          data={queues}
          page={page}
          pageChange={setPage}
          size={size}
          sizeChange={setSize}
          total={total}
          noData={
            <DataTableNoData title={noDataText}>
              {noData && (
                <Button
                  variant="outline"
                  onClick={() => setOpenDialog(true)}
                >
                  <Plus className="mr-2 h-4 w-4" />
                  Create your first queue
                </Button>
              )}
            </DataTableNoData>
          }
        />
      </div>

      <CreateAnnotationQueueDialog
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </PageLayout>
  );
};

export default AnnotationQueuesPage;
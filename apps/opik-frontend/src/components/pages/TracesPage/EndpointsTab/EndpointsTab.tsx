import React, { useCallback, useMemo, useState } from "react";
import { CellContext, ColumnDef } from "@tanstack/react-table";
import { Plus, Pencil, Trash2, Eye, EyeOff, Copy } from "lucide-react";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { useToast } from "@/components/ui/use-toast";
import Loader from "@/components/shared/Loader/Loader";
import useEndpoints, { Endpoint } from "@/api/endpoints/useEndpoints";
import useCreateEndpoint from "@/api/endpoints/useCreateEndpoint";
import useUpdateEndpoint from "@/api/endpoints/useUpdateEndpoint";
import useDeleteEndpoint from "@/api/endpoints/useDeleteEndpoint";
import AddEndpointDialog from "./AddEndpointDialog";
import CallEndpointDialog from "./CallEndpointDialog";

const getRowId = (row: Endpoint) => row.id;

type EndpointsTabProps = {
  projectId: string;
};

const EndpointsTab: React.FC<EndpointsTabProps> = ({ projectId }) => {
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [editingEndpoint, setEditingEndpoint] = useState<Endpoint | null>(null);
  const [callEndpoint, setCallEndpoint] = useState<Endpoint | null>(null);
  const [visibleSecrets, setVisibleSecrets] = useState<Set<string>>(new Set());
  const { toast } = useToast();

  const { data, isPending, isError } = useEndpoints({ projectId });
  const createMutation = useCreateEndpoint();
  const updateMutation = useUpdateEndpoint();
  const deleteMutation = useDeleteEndpoint();

  const endpoints = data?.content ?? [];

  const toggleSecretVisibility = useCallback((id: string) => {
    setVisibleSecrets((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const handleCopySecret = useCallback(
    (secret: string) => {
      navigator.clipboard.writeText(secret);
      toast({ description: "Secret copied to clipboard" });
    },
    [toast],
  );

  const handleAdd = useCallback(
    (endpoint: { name: string; url: string; secret: string; schemaJson: string | null }) => {
      createMutation.mutate(
        {
          project_id: projectId,
          name: endpoint.name,
          url: endpoint.url,
          secret: endpoint.secret,
          schema_json: endpoint.schemaJson,
        },
        {
          onSuccess: () => {
            setShowAddDialog(false);
            toast({ description: "Endpoint added successfully" });
          },
          onError: () => {
            toast({ description: "Failed to add endpoint", variant: "destructive" });
          },
        },
      );
    },
    [createMutation, projectId, toast],
  );

  const handleEdit = useCallback((endpoint: Endpoint) => {
    setEditingEndpoint(endpoint);
    setShowAddDialog(true);
  }, []);

  const handleUpdate = useCallback(
    (updated: { name: string; url: string; secret: string; schemaJson: string | null }) => {
      if (!editingEndpoint) return;
      updateMutation.mutate(
        {
          id: editingEndpoint.id,
          name: updated.name,
          url: updated.url,
          secret: updated.secret,
          schema_json: updated.schemaJson,
        },
        {
          onSuccess: () => {
            setEditingEndpoint(null);
            setShowAddDialog(false);
            toast({ description: "Endpoint updated successfully" });
          },
          onError: () => {
            toast({ description: "Failed to update endpoint", variant: "destructive" });
          },
        },
      );
    },
    [editingEndpoint, updateMutation, toast],
  );

  const handleDelete = useCallback(
    (id: string) => {
      deleteMutation.mutate(id, {
        onSuccess: () => {
          toast({ description: "Endpoint deleted" });
        },
        onError: () => {
          toast({ description: "Failed to delete endpoint", variant: "destructive" });
        },
      });
    },
    [deleteMutation, toast],
  );

  const handleCloseDialog = useCallback(() => {
    setShowAddDialog(false);
    setEditingEndpoint(null);
  }, []);

  const handleRowClick = useCallback((endpoint: Endpoint) => {
    setCallEndpoint(endpoint);
  }, []);

  const NameCell = useCallback(
    (context: CellContext<Endpoint, string>) => {
      const value = context.getValue();
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <span className="font-medium">{value}</span>
        </CellWrapper>
      );
    },
    [],
  );

  const UrlCell = useCallback(
    (context: CellContext<Endpoint, string>) => {
      const value = context.getValue();
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <code className="truncate font-mono text-sm">{value}</code>
        </CellWrapper>
      );
    },
    [],
  );

  const SecretCell = useCallback(
    (context: CellContext<Endpoint, string | undefined>) => {
      const value = context.getValue();
      const id = context.row.original.id;
      const isVisible = visibleSecrets.has(id);

      if (!value) {
        return (
          <CellWrapper
            metadata={context.column.columnDef.meta}
            tableMetadata={context.table.options.meta}
          >
            <span className="text-muted-slate">—</span>
          </CellWrapper>
        );
      }

      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <div className="flex items-center gap-2">
            <code className="font-mono text-sm">
              {isVisible ? value : "••••••••••••"}
            </code>
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={(e) => {
                e.stopPropagation();
                toggleSecretVisibility(id);
              }}
            >
              {isVisible ? (
                <EyeOff className="size-3.5" />
              ) : (
                <Eye className="size-3.5" />
              )}
            </Button>
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={(e) => {
                e.stopPropagation();
                handleCopySecret(value);
              }}
            >
              <Copy className="size-3.5" />
            </Button>
          </div>
        </CellWrapper>
      );
    },
    [visibleSecrets, toggleSecretVisibility, handleCopySecret],
  );

  const SchemaCell = useCallback(
    (context: CellContext<Endpoint, string | null>) => {
      const value = context.getValue();
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          {value ? (
            <Tag variant="green" size="sm">
              Configured
            </Tag>
          ) : (
            <span className="text-muted-slate">—</span>
          )}
        </CellWrapper>
      );
    },
    [],
  );

  const ActionsCell = useCallback(
    (context: CellContext<Endpoint, unknown>) => {
      const endpoint = context.row.original;
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
          stopClickPropagation
        >
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleEdit(endpoint)}
            >
              <Pencil className="size-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleDelete(endpoint.id)}
            >
              <Trash2 className="size-4 text-destructive" />
            </Button>
          </div>
        </CellWrapper>
      );
    },
    [handleEdit, handleDelete],
  );

  const columns: ColumnDef<Endpoint>[] = useMemo(
    () => [
      {
        accessorKey: "name",
        header: "Name",
        cell: NameCell,
        size: 200,
      },
      {
        accessorKey: "url",
        header: "URL",
        cell: UrlCell,
        size: 350,
      },
      {
        accessorKey: "secret",
        header: "Secret",
        cell: SecretCell,
        size: 200,
      },
      {
        accessorKey: "schema_json",
        header: "Schema",
        cell: SchemaCell,
        size: 100,
      },
      {
        id: "actions",
        header: "",
        cell: ActionsCell,
        size: 80,
      },
    ],
    [NameCell, UrlCell, SecretCell, SchemaCell, ActionsCell],
  );

  if (isPending) {
    return (
      <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
        <Loader />
      </PageBodyStickyContainer>
    );
  }

  if (isError) {
    return (
      <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
        <DataTableNoData title="Error loading endpoints">
          Failed to load endpoints. Please try again.
        </DataTableNoData>
      </PageBodyStickyContainer>
    );
  }

  return (
    <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-medium">Agent Endpoints</h2>
          <p className="text-sm text-muted-slate">
            Configure endpoints to connect to your agents
          </p>
        </div>
        <Button onClick={() => setShowAddDialog(true)}>
          <Plus className="mr-1.5 size-4" />
          Add Endpoint
        </Button>
      </div>

      {endpoints.length === 0 ? (
        <DataTableNoData title="No endpoints configured">
          Add an endpoint to connect Opik to your agent.
        </DataTableNoData>
      ) : (
        <DataTable
          columns={columns}
          data={endpoints}
          getRowId={getRowId}
          noData={<DataTableNoData title="No endpoints" />}
          TableWrapper={PageBodyStickyTableWrapper}
          onRowClick={handleRowClick}
        />
      )}

      <AddEndpointDialog
        open={showAddDialog}
        onClose={handleCloseDialog}
        onSubmit={editingEndpoint ? handleUpdate : handleAdd}
        endpoint={editingEndpoint}
        isLoading={createMutation.isPending || updateMutation.isPending}
      />

      <CallEndpointDialog
        open={!!callEndpoint}
        onClose={() => setCallEndpoint(null)}
        endpoint={callEndpoint}
      />
    </PageBodyStickyContainer>
  );
};

export default EndpointsTab;

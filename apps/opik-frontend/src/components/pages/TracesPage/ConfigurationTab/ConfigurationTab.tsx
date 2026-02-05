import React, { useCallback, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  CellContext,
  ColumnDef,
  ColumnPinningState,
  RowSelectionState,
} from "@tanstack/react-table";
import { FlaskConical, Pencil, FileText, Sparkles, Split } from "lucide-react";
import useAppStore from "@/store/AppStore";

import { COLUMN_SELECT_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { formatDate } from "@/lib/date";
import Loader from "@/components/shared/Loader/Loader";
import useConfigVariables, {
  ConfigVariable,
  PromptValue,
} from "@/api/config/useConfigVariables";
import useUpdateConfigVariable from "@/api/config/useUpdateConfigVariable";
import ConfigurationSidePanel from "./ConfigurationSidePanel";
import CreateExperimentDialog from "./CreateExperimentDialog";
import OptimizeChatDialog from "./OptimizeChatDialog";
import ABTestDialog from "./ABTestDialog";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";

const getRowId = (row: ConfigVariable) => row.id;

const parseKeyParts = (key: string): { group: string; name: string } => {
  const dotIndex = key.indexOf(".");
  if (dotIndex === -1) {
    return { group: "", name: key };
  }
  return {
    group: key.substring(0, dotIndex),
    name: key.substring(dotIndex + 1),
  };
};

const generateFakeCommitId = (key: string, version: number): string => {
  let hash = 0;
  const str = `${key}-${version}`;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) - hash + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash).toString(16).padStart(7, "0").substring(0, 7);
};

const parsePromptValue = (value: unknown): PromptValue | null => {
  // Already an object with prompt_name
  if (value && typeof value === "object" && "prompt_name" in value) {
    return value as PromptValue;
  }
  // JSON string
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed.prompt_name === "string") {
        return parsed as PromptValue;
      }
    } catch {
      // Not JSON
    }
  }
  return null;
};

const KeyCell = (context: CellContext<ConfigVariable, string>) => {
  const { column, table, row } = context;
  const value = context.getValue();
  const { version, type } = row.original;
  const { name } = parseKeyParts(value);

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
    >
      <div className="flex items-center gap-2">
        {type === "prompt" && (
          <FileText className="size-4 shrink-0 text-muted-slate" />
        )}
        <span className="truncate text-sm">{name}</span>
        {version > 1 && type !== "prompt" && (
          <Tag variant="gray" size="sm">
            v{version}
          </Tag>
        )}
      </div>
    </CellWrapper>
  );
};

const CurrentValueCell = (
  context: CellContext<ConfigVariable, string | number | boolean>,
) => {
  const { column, table, row } = context;
  const value = context.getValue();
  const { type, version, key } = row.original;

  const promptData = type === "prompt" ? parsePromptValue(value) : null;

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
    >
      {type === "prompt" ? (
        <div className="flex flex-col">
          <span className="truncate text-sm">
            {promptData?.prompt_name ?? String(value)}
          </span>
          <div className="flex items-center gap-1 text-xs text-muted-slate">
            <span>╰─</span>
            <code className="font-mono">
              {generateFakeCommitId(key, version)}
            </code>
          </div>
        </div>
      ) : type === "boolean" ? (
        <Tag variant={value ? "green" : "gray"} size="sm">
          {String(value)}
        </Tag>
      ) : (
        <span className="truncate">{String(value)}</span>
      )}
    </CellWrapper>
  );
};

const FallbackCell = (
  context: CellContext<ConfigVariable, string | number | boolean>,
) => {
  const { column, table, row } = context;
  const value = context.getValue();
  const { type, key } = row.original;

  const promptData = type === "prompt" ? parsePromptValue(value) : null;

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
    >
      {type === "prompt" ? (
        <div className="flex flex-col">
          <span className="truncate text-sm text-muted-slate">
            {promptData?.prompt_name ?? String(value)}
          </span>
          <div className="flex items-center gap-1 text-xs text-muted-slate">
            <span>╰─</span>
            <code className="font-mono">{generateFakeCommitId(key, 1)}</code>
          </div>
        </div>
      ) : (
        <span className="truncate text-muted-slate">
          {String(value)}
        </span>
      )}
    </CellWrapper>
  );
};

const TypeCell = (context: CellContext<ConfigVariable, string>) => {
  const { column, table } = context;
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
    >
      <Tag variant="gray" size="sm" className="capitalize">
        {value}
      </Tag>
    </CellWrapper>
  );
};

const ExperimentCountCell = (context: CellContext<ConfigVariable, number>) => {
  const { column, table } = context;
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
    >
      {value > 0 ? (
        <Tag variant="purple" size="sm">
          {value} experiment{value > 1 ? "s" : ""}
        </Tag>
      ) : (
        <span className="text-muted-slate">—</span>
      )}
    </CellWrapper>
  );
};

const DEFAULT_COLUMNS: ColumnData<ConfigVariable>[] = [
  {
    id: "currentValue",
    label: "Current Value",
    type: COLUMN_TYPE.string,
    cell: CurrentValueCell as never,
  },
  {
    id: "fallback",
    label: "Fallback",
    type: COLUMN_TYPE.string,
    cell: FallbackCell as never,
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.string,
    cell: TypeCell as never,
  },
  {
    id: "experimentCount",
    label: "Experiments",
    type: COLUMN_TYPE.number,
    cell: ExperimentCountCell as never,
  },
  {
    id: "lastUsed",
    label: "Last Used",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.lastUsed),
  },
];

const DEFAULT_SELECTED_COLUMNS = [
  "currentValue",
  "fallback",
  "type",
  "experimentCount",
  "lastUsed",
];

const COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, "key"],
  right: [],
};

type ConfigurationTabProps = {
  projectId: string;
};

const ConfigurationTab: React.FC<ConfigurationTabProps> = ({ projectId }) => {
  // Use "default" for config backend - the Python SDK registers with project_id="default"
  const configProjectId = "default";
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [selectedVariable, setSelectedVariable] =
    useState<ConfigVariable | null>(null);
  const [panelMode, setPanelMode] = useState<"update" | "experiment" | null>(
    null,
  );
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [showOptimizeDialog, setShowOptimizeDialog] = useState(false);
  const [showABTestDialog, setShowABTestDialog] = useState(false);

  const { data: configData, isPending, isError } = useConfigVariables({ projectId: configProjectId });
  const updateMutation = useUpdateConfigVariable({ projectId: configProjectId });

  const configVariables = configData?.variables;

  const handleUpdate = useCallback((variable: ConfigVariable) => {
    setSelectedVariable(variable);
    setPanelMode("update");
  }, []);

  const handleExperiment = useCallback((variable: ConfigVariable) => {
    setSelectedVariable(variable);
    setPanelMode("experiment");
  }, []);

  const handleClosePanel = useCallback(() => {
    setSelectedVariable(null);
    setPanelMode(null);
  }, []);

  const handleSaveValue = useCallback(
    (key: string, value: string | number | boolean) => {
      updateMutation.mutate({ key, value });
    },
    [updateMutation],
  );

  const ActionsCell = useCallback(
    (context: CellContext<ConfigVariable, unknown>) => {
      const { row, column, table } = context;
      return (
        <CellWrapper
          metadata={column.columnDef.meta}
          tableMetadata={table.options.meta}
          stopClickPropagation
        >
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleUpdate(row.original)}
            >
              <Pencil className="size-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleExperiment(row.original)}
            >
              <FlaskConical className="size-4" />
            </Button>
          </div>
        </CellWrapper>
      );
    },
    [handleUpdate, handleExperiment],
  );

  const selectedVariables: ConfigVariable[] = useMemo(() => {
    if (!configVariables) return [];
    return configVariables.filter((v) => rowSelection[v.id]);
  }, [configVariables, rowSelection]);

  const handleCreateExperiment = useCallback(() => {
    setShowCreateDialog(true);
  }, []);

  const handleExperimentCreated = useCallback(
    (experimentId: string) => {
      setRowSelection({});
      setShowCreateDialog(false);
      navigate({
        to: "/$workspaceName/experiments/live/$experimentId",
        params: { workspaceName, experimentId },
      });
    },
    [navigate, workspaceName]
  );

  const columns: ColumnDef<ConfigVariable>[] = useMemo(() => {
    return [
      generateSelectColumDef<ConfigVariable>(),
      {
        accessorKey: "key",
        header: "Key",
        cell: KeyCell,
        size: 200,
        enableResizing: true,
      } as ColumnDef<ConfigVariable>,
      ...convertColumnDataToColumn<ConfigVariable, ConfigVariable>(
        DEFAULT_COLUMNS,
        {
          selectedColumns: DEFAULT_SELECTED_COLUMNS,
        },
      ),
      {
        accessorKey: "actions",
        header: "",
        cell: ActionsCell,
        size: 80,
        enableResizing: false,
        enableHiding: false,
        enableSorting: false,
      } as ColumnDef<ConfigVariable>,
    ];
  }, [ActionsCell]);

  const groupedData = useMemo(() => {
    if (!configVariables) return [];

    const groups = new Map<string, ConfigVariable[]>();
    for (const variable of configVariables) {
      const { group } = parseKeyParts(variable.key);
      const groupKey = group || "Other";
      if (!groups.has(groupKey)) {
        groups.set(groupKey, []);
      }
      groups.get(groupKey)!.push(variable);
    }

    return Array.from(groups.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([groupName, variables]) => ({
        groupName,
        variables: variables.sort(
          (a, b) =>
            new Date(b.lastUsed).getTime() - new Date(a.lastUsed).getTime(),
        ),
      }));
  }, [configVariables]);

  const allVariables = useMemo(() => {
    return groupedData.flatMap((g) => g.variables);
  }, [groupedData]);

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
        <DataTableNoData title="Backend not available">
          Start the config backend with: uv run python -m opik_config
        </DataTableNoData>
      </PageBodyStickyContainer>
    );
  }

  if (allVariables.length === 0) {
    return (
      <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
        <DataTableNoData title="No configuration variables">
          Configuration variables will appear here once your agent starts using
          the @agent_config decorator.
        </DataTableNoData>
      </PageBodyStickyContainer>
    );
  }

  return (
    <>
      {selectedVariables.length > 0 && (
        <div className="mb-4 flex items-center justify-between rounded-lg border bg-muted/30 p-3">
          <span className="text-sm">
            {selectedVariables.length} variable
            {selectedVariables.length > 1 ? "s" : ""} selected
          </span>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              className="border-amber-500 text-amber-600 hover:bg-amber-50 hover:text-amber-700"
              onClick={() => setShowOptimizeDialog(true)}
            >
              <Sparkles className="mr-1.5 size-4" />
              Optimize
            </Button>
            <Button
              size="sm"
              variant="outline"
              className="border-blue-500 text-blue-600 hover:bg-blue-50 hover:text-blue-700"
              onClick={() => setShowABTestDialog(true)}
            >
              <Split className="mr-1.5 size-4" />
              A/B Test
            </Button>
            <Button size="sm" onClick={handleCreateExperiment}>
              <FlaskConical className="mr-1.5 size-4" />
              Create Experiment
            </Button>
          </div>
        </div>
      )}
      <div className="flex flex-col gap-6">
        {groupedData.map(({ groupName, variables }) => (
          <div key={groupName}>
            <div className="mb-2 flex items-center gap-2 pl-4">
              <span className="font-medium text-foreground">{groupName}</span>
              <span className="text-sm text-muted-slate">
                {variables.length} variable{variables.length > 1 ? "s" : ""}
              </span>
            </div>
            <DataTable
              columns={columns}
              data={variables}
              getRowId={getRowId}
              onRowClick={handleUpdate}
              activeRowId={selectedVariable?.id}
              noData={<DataTableNoData title="No configuration variables" />}
              TableWrapper={PageBodyStickyTableWrapper}
              stickyHeader
              selectionConfig={{
                rowSelection,
                setRowSelection,
              }}
              columnPinning={COLUMN_PINNING}
            />
          </div>
        ))}
      </div>
      <ConfigurationSidePanel
        variable={selectedVariable}
        mode={panelMode}
        open={!!selectedVariable && !!panelMode}
        onClose={handleClosePanel}
        projectId={configProjectId}
        onSave={handleSaveValue}
        isSaving={updateMutation.isPending}
      />
      <CreateExperimentDialog
        open={showCreateDialog}
        onClose={() => setShowCreateDialog(false)}
        selectedVariables={selectedVariables}
        projectId={configProjectId}
        onSuccess={handleExperimentCreated}
      />
      <OptimizeChatDialog
        open={showOptimizeDialog}
        onClose={() => setShowOptimizeDialog(false)}
        selectedVariables={selectedVariables}
      />
      <ABTestDialog
        open={showABTestDialog}
        onClose={() => setShowABTestDialog(false)}
        selectedVariables={selectedVariables}
        projectId={configProjectId}
        onSuccess={(testId) => {
          setRowSelection({});
          setShowABTestDialog(false);
          navigate({
            to: "/$workspaceName/experiments/live/$experimentId",
            params: { workspaceName, experimentId: testId },
          });
        }}
      />
    </>
  );
};

export default ConfigurationTab;

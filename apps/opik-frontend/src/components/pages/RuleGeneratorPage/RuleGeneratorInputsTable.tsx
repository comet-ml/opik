import React, { useCallback, useMemo } from "react";
import { Check, ListRestart, Loader2, Plus, Trash2, X } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Textarea } from "@/components/ui/textarea";
import { createDefaultSample } from "./defaultInputsSample";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

export interface RuleGeneratorInput {
  id: string;
  inputType: string;
  userInput: string;
  result: string;
}

const STORAGE_KEY = "opik_rule_generator_inputs";

export const useRuleGeneratorInputs = () => {
  return useLocalStorageState<RuleGeneratorInput[]>(STORAGE_KEY, {
    defaultValue: [],
  });
};

const REQUIRED_FIELDS = [
  "scope",
  "evaluator_name",
  "judge_prompts",
  "score_schema",
  "expected_output_schema",
] as const;

const validateResult = (
  result: string,
): {
  isDefaultRule: boolean;
  isValidJson: boolean;
  missingFields: string[];
} => {
  const trimmed = result.trim();
  if (!trimmed)
    return { isDefaultRule: false, isValidJson: false, missingFields: [] };
  if (trimmed === "OPIK_DEFAULT_RULE")
    return { isDefaultRule: true, isValidJson: false, missingFields: [] };
  try {
    const parsed = JSON.parse(trimmed);
    if (typeof parsed !== "object" || parsed === null) {
      return { isDefaultRule: false, isValidJson: false, missingFields: [] };
    }
    const missingFields = REQUIRED_FIELDS.filter((field) => !(field in parsed));
    return { isDefaultRule: false, isValidJson: true, missingFields };
  } catch {
    return { isDefaultRule: false, isValidJson: false, missingFields: [] };
  }
};

interface RuleGeneratorInputsTableProps {
  loadingRowIds?: Set<string>;
  selectedRowIds: Set<string>;
  onSelectionChange: (ids: Set<string>) => void;
}

const RuleGeneratorInputsTable: React.FC<RuleGeneratorInputsTableProps> = ({
  loadingRowIds = new Set(),
  selectedRowIds: selected,
  onSelectionChange: setSelected,
}) => {
  const [rows, setRows] = useRuleGeneratorInputs();

  const allSelected = useMemo(
    () => rows.length > 0 && selected.size === rows.length,
    [rows.length, selected.size],
  );

  const someSelected = useMemo(
    () => selected.size > 0 && selected.size < rows.length,
    [rows.length, selected.size],
  );

  const toggleSelectAll = useCallback(() => {
    if (allSelected) {
      setSelected(new Set());
    } else {
      setSelected(new Set(rows.map((r) => r.id)));
    }
  }, [allSelected, rows, setSelected]);

  const toggleSelect = useCallback(
    (id: string) => {
      const next = new Set(selected);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      setSelected(next);
    },
    [selected, setSelected],
  );

  const deleteSelected = useCallback(() => {
    setRows((prev) => prev.filter((r) => !selected.has(r.id)));
    setSelected(new Set());
  }, [setRows, selected, setSelected]);

  const loadDefaultSample = useCallback(() => {
    setRows(createDefaultSample());
    setSelected(new Set());
  }, [setRows, setSelected]);

  const addRow = useCallback(() => {
    setRows((prev) => [
      ...prev,
      { id: crypto.randomUUID(), inputType: "", userInput: "", result: "" },
    ]);
  }, [setRows]);

  const deleteRow = useCallback(
    (id: string) => {
      setRows((prev) => prev.filter((r) => r.id !== id));
      const next = new Set(selected);
      next.delete(id);
      setSelected(next);
    },
    [setRows, selected, setSelected],
  );

  const updateRow = useCallback(
    (
      id: string,
      field: keyof Omit<RuleGeneratorInput, "id">,
      value: string,
    ) => {
      setRows((prev) =>
        prev.map((r) => (r.id === id ? { ...r, [field]: value } : r)),
      );
    },
    [setRows],
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h2 className="comet-title-s">Inputs</h2>
          {selected.size > 0 && (
            <Button variant="outline" size="sm" onClick={deleteSelected}>
              <Trash2 className="mr-1 size-4" />
              Delete selected ({selected.size})
            </Button>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={loadDefaultSample}>
            <ListRestart className="mr-1 size-4" />
            Load Juan&apos;s sample
          </Button>
          <Button variant="outline" size="sm" onClick={addRow}>
            <Plus className="mr-1 size-4" />
            Add row
          </Button>
        </div>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[40px]">
              <div
                data-header-wrapper="true"
                className="flex items-center justify-center p-2"
              >
                <Checkbox
                  checked={
                    allSelected ? true : someSelected ? "indeterminate" : false
                  }
                  onCheckedChange={toggleSelectAll}
                />
              </div>
            </TableHead>
            <TableHead>
              <div data-header-wrapper="true" className="p-2">
                User input
              </div>
            </TableHead>
            <TableHead className="w-[200px]">
              <div data-header-wrapper="true" className="p-2">
                Launch context (optional)
              </div>
            </TableHead>
            <TableHead>
              <div data-header-wrapper="true" className="p-2">
                Result
              </div>
            </TableHead>
            <TableHead className="w-[160px]">
              <div data-header-wrapper="true" className="p-2">
                Checks
              </div>
            </TableHead>
            <TableHead className="w-[60px]">
              <div data-header-wrapper="true" className="p-2" />
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={6}>
                <div className="p-4 text-center text-muted-slate">
                  No inputs yet. Click &quot;Add row&quot; to get started.
                </div>
              </TableCell>
            </TableRow>
          )}
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell className="align-top">
                <div
                  data-cell-wrapper="true"
                  className="flex items-center justify-center p-1 pt-3"
                >
                  <Checkbox
                    checked={selected.has(row.id)}
                    onCheckedChange={() => toggleSelect(row.id)}
                  />
                </div>
              </TableCell>
              <TableCell className="align-top">
                <div data-cell-wrapper="true" className="p-1">
                  <Textarea
                    value={row.userInput}
                    placeholder="e.g. Check for hallucinations"
                    className="min-h-48 text-sm"
                    onChange={(e) =>
                      updateRow(row.id, "userInput", e.target.value)
                    }
                  />
                </div>
              </TableCell>
              <TableCell className="align-top">
                <div data-cell-wrapper="true" className="p-1">
                  <Textarea
                    value={row.inputType}
                    placeholder="e.g. trace"
                    className="min-h-48 text-sm"
                    onChange={(e) =>
                      updateRow(row.id, "inputType", e.target.value)
                    }
                  />
                </div>
              </TableCell>
              <TableCell className="align-top">
                <div data-cell-wrapper="true" className="relative p-1">
                  {loadingRowIds.has(row.id) && (
                    <div className="absolute inset-0 z-10 flex items-center justify-center rounded-md bg-muted/60">
                      <Loader2 className="size-5 animate-spin text-muted-slate" />
                    </div>
                  )}
                  <Textarea
                    value={row.result}
                    placeholder="Result"
                    className="min-h-48 text-sm"
                    onChange={(e) =>
                      updateRow(row.id, "result", e.target.value)
                    }
                  />
                </div>
              </TableCell>
              <TableCell className="align-top">
                <div data-cell-wrapper="true" className="p-1 pt-3">
                  {row.result.trim() &&
                    (() => {
                      const { isDefaultRule, isValidJson, missingFields } =
                        validateResult(row.result);
                      if (isDefaultRule) {
                        return (
                          <div className="flex items-center gap-1.5 text-xs">
                            <Check className="size-3.5 text-green-600" />
                            <span>Default rule</span>
                          </div>
                        );
                      }
                      return (
                        <div className="flex flex-col gap-1.5 text-xs">
                          <div className="flex items-center gap-1.5">
                            {isValidJson ? (
                              <Check className="size-3.5 text-green-600" />
                            ) : (
                              <X className="size-3.5 text-red-500" />
                            )}
                            <span>Valid JSON</span>
                          </div>
                          <div className="flex items-center gap-1.5">
                            {isValidJson && missingFields.length === 0 ? (
                              <Check className="size-3.5 text-green-600" />
                            ) : (
                              <X className="size-3.5 text-red-500" />
                            )}
                            <span>Required fields</span>
                          </div>
                          {isValidJson && missingFields.length > 0 && (
                            <div className="ml-5 text-muted-slate">
                              Missing: {missingFields.join(", ")}
                            </div>
                          )}
                        </div>
                      );
                    })()}
                </div>
              </TableCell>
              <TableCell className="align-top">
                <div
                  data-cell-wrapper="true"
                  className="flex justify-center p-1 pt-3"
                >
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => deleteRow(row.id)}
                  >
                    <Trash2 className="size-4 text-light-slate" />
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default RuleGeneratorInputsTable;

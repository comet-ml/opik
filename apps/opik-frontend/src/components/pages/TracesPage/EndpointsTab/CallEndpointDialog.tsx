import React, { useCallback, useState, useMemo } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Loader2, Play, CheckCircle, XCircle, Beaker } from "lucide-react";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useConfigVariables from "@/api/config/useConfigVariables";
import useAppStore from "@/store/AppStore";
import { Endpoint } from "@/api/endpoints/useEndpoints";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

type CallEndpointDialogProps = {
  open: boolean;
  onClose: () => void;
  endpoint: Endpoint | null;
};

type CallResult = {
  success: boolean;
  status?: number;
  data?: unknown;
  error?: string;
};

const CallEndpointDialog: React.FC<CallEndpointDialogProps> = ({
  open,
  onClose,
  endpoint,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [mode, setMode] = useState<"dataset" | "json">("json");
  const [selectedDatasetId, setSelectedDatasetId] = useState<string>("");
  const [selectedItemId, setSelectedItemId] = useState<string>("");
  const [selectedExperimentId, setSelectedExperimentId] = useState<string>("");
  const [rawJson, setRawJson] = useState<string>("{\n  \n}");
  const [isLoading, setIsLoading] = useState(false);
  const [result, setResult] = useState<CallResult | null>(null);

  const { data: datasetsData } = useDatasetsList(
    { workspaceName, page: 1, size: 100 },
    { enabled: open && mode === "dataset" },
  );

  const { data: itemsData } = useDatasetItemsList(
    { datasetId: selectedDatasetId, page: 1, size: 100 },
    { enabled: open && mode === "dataset" && !!selectedDatasetId },
  );

  const { data: configData } = useConfigVariables({ projectId: "default" });

  const datasets = datasetsData?.content ?? [];
  const datasetItems = itemsData?.content ?? [];
  const experiments = configData?.experiments ?? [];

  const selectedItem = useMemo(() => {
    return datasetItems.find((item) => item.id === selectedItemId);
  }, [datasetItems, selectedItemId]);

  const selectedExperiment = useMemo(() => {
    return experiments.find((exp) => exp.id === selectedExperimentId);
  }, [experiments, selectedExperimentId]);

  const getRequestBody = useCallback(() => {
    if (mode === "json") {
      try {
        return JSON.parse(rawJson);
      } catch {
        return null;
      }
    }
    if (selectedItem) {
      return selectedItem.data;
    }
    return null;
  }, [mode, rawJson, selectedItem]);

  const handleCall = useCallback(async () => {
    if (!endpoint) return;

    const body = getRequestBody();
    if (!body) {
      setResult({ success: false, error: "Invalid JSON" });
      return;
    }

    setIsLoading(true);
    setResult(null);

    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        Authorization: `Bearer ${endpoint.secret}`,
      };

      if (selectedExperimentId) {
        headers["X-Opik-Experiment-Id"] = selectedExperimentId;
      }

      const response = await fetch(endpoint.url, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
      });

      const data = await response.json().catch(() => null);

      setResult({
        success: response.ok,
        status: response.status,
        data,
        error: response.ok ? undefined : `HTTP ${response.status}`,
      });
    } catch (err) {
      setResult({
        success: false,
        error: err instanceof Error ? err.message : "Request failed",
      });
    } finally {
      setIsLoading(false);
    }
  }, [endpoint, getRequestBody, selectedExperimentId]);

  const handleClose = useCallback(() => {
    setResult(null);
    setRawJson("{\n  \n}");
    setSelectedDatasetId("");
    setSelectedItemId("");
    setSelectedExperimentId("");
    onClose();
  }, [onClose]);

  const isValidJson = useMemo(() => {
    if (mode !== "json") return true;
    try {
      JSON.parse(rawJson);
      return true;
    } catch {
      return false;
    }
  }, [mode, rawJson]);

  const canSubmit =
    mode === "json" ? isValidJson : !!selectedDatasetId && !!selectedItemId;

  if (!endpoint) return null;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Call Agent: {endpoint.name}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div className="text-sm text-muted-slate">
            Endpoint: <code className="font-mono">{endpoint.url}</code>
          </div>

          <div>
            <Label className="mb-1.5 flex items-center gap-1.5">
              <Beaker className="size-4" />
              Experiment (Optional)
            </Label>
            <Select value={selectedExperimentId} onValueChange={setSelectedExperimentId}>
              <SelectTrigger>
                <SelectValue placeholder="No experiment (use production config)" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="">No experiment</SelectItem>
                {experiments.map((exp) => (
                  <SelectItem key={exp.id} value={exp.id}>
                    {exp.name}
                    {exp.isAb && " (A/B Test)"}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {selectedExperiment && (
              <div className="mt-2 rounded border bg-muted/50 p-2 text-xs">
                <div className="font-medium">
                  {selectedExperiment.isAb ? "A/B Test" : "Experiment"}: {selectedExperiment.name}
                </div>
                <code className="text-muted-slate">{selectedExperiment.id}</code>
                <div className="mt-1 text-muted-slate">
                  {selectedExperiment.overrides.length} override(s)
                  {selectedExperiment.isAb && selectedExperiment.distribution && (
                    <span>
                      {" "}
                      &bull; Split: {Object.entries(selectedExperiment.distribution)
                        .map(([k, v]) => `${k}:${v}%`)
                        .join(" / ")}
                    </span>
                  )}
                </div>
              </div>
            )}
          </div>

          <Tabs value={mode} onValueChange={(v) => setMode(v as "dataset" | "json")}>
            <TabsList>
              <TabsTrigger value="json">Raw JSON</TabsTrigger>
              <TabsTrigger value="dataset">Dataset Item</TabsTrigger>
            </TabsList>

            <TabsContent value="json" className="space-y-3">
              <div>
                <Label>Request Body (JSON)</Label>
                <Textarea
                  className="mt-1.5 min-h-[200px] font-mono text-sm"
                  value={rawJson}
                  onChange={(e) => setRawJson(e.target.value)}
                  placeholder='{"input": "Hello, world!"}'
                />
                {!isValidJson && (
                  <p className="mt-1 text-sm text-destructive">Invalid JSON</p>
                )}
              </div>
            </TabsContent>

            <TabsContent value="dataset" className="space-y-3">
              <div>
                <Label>Select Dataset</Label>
                <Select value={selectedDatasetId} onValueChange={setSelectedDatasetId}>
                  <SelectTrigger className="mt-1.5">
                    <SelectValue placeholder="Choose a dataset..." />
                  </SelectTrigger>
                  <SelectContent>
                    {datasets.map((ds) => (
                      <SelectItem key={ds.id} value={ds.id}>
                        {ds.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {selectedDatasetId && (
                <div>
                  <Label>Select Item</Label>
                  <Select value={selectedItemId} onValueChange={setSelectedItemId}>
                    <SelectTrigger className="mt-1.5">
                      <SelectValue placeholder="Choose an item..." />
                    </SelectTrigger>
                    <SelectContent>
                      {datasetItems.map((item, idx) => (
                        <SelectItem key={item.id} value={item.id}>
                          Item {idx + 1}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              {selectedItem && (
                <div>
                  <Label>Item Data (Preview)</Label>
                  <div className="mt-1.5 max-h-[150px] overflow-auto rounded border bg-muted p-2">
                    <SyntaxHighlighter data={selectedItem.data as object} />
                  </div>
                </div>
              )}
            </TabsContent>
          </Tabs>

          <div className="flex items-center gap-3">
            <Button onClick={handleCall} disabled={!canSubmit || isLoading}>
              {isLoading ? (
                <Loader2 className="mr-1.5 size-4 animate-spin" />
              ) : (
                <Play className="mr-1.5 size-4" />
              )}
              Call Endpoint
            </Button>
          </div>

          {result && (
            <div className="rounded border p-3">
              <div className="mb-2 flex items-center gap-2">
                {result.success ? (
                  <>
                    <CheckCircle className="size-4 text-green-600" />
                    <span className="font-medium text-green-600">
                      Success (HTTP {result.status})
                    </span>
                  </>
                ) : (
                  <>
                    <XCircle className="size-4 text-destructive" />
                    <span className="font-medium text-destructive">
                      {result.error}
                    </span>
                  </>
                )}
              </div>
              {result.data ? (
                <div className="max-h-[200px] overflow-auto rounded bg-muted p-2">
                  <SyntaxHighlighter data={result.data as object} />
                </div>
              ) : null}
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default CallEndpointDialog;

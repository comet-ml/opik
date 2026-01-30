import React, { useMemo, useState, useCallback } from "react";
import { useNavigate, useParams, useSearch } from "@tanstack/react-router";
import {
  ArrowLeft,
  Copy,
  FlaskConical,
  Split,
  Play,
  Loader2,
  CheckCircle,
  XCircle,
  Plug,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useToast } from "@/components/ui/use-toast";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import Loader from "@/components/shared/Loader/Loader";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import useAppStore from "@/store/AppStore";
import useConfigVariables from "@/api/config/useConfigVariables";
import useAllEndpoints from "@/api/endpoints/useAllEndpoints";
import { formatDate } from "@/lib/date";

type CallResult = {
  success: boolean;
  status?: number;
  data?: unknown;
  error?: string;
};

const LiveExperimentPage: React.FC = () => {
  const { experimentId } = useParams({ strict: false }) as {
    experimentId: string;
  };
  const search = useSearch({ strict: false }) as { name?: string };
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const [selectedEndpointId, setSelectedEndpointId] = useState<string>("");
  const [rawJson, setRawJson] = useState<string>('{\n  "input": "Hello"\n}');
  const [isLoading, setIsLoading] = useState(false);
  const [result, setResult] = useState<CallResult | null>(null);

  const { data: configData, isPending, isError } = useConfigVariables({
    projectId: "default",
  });

  const { data: endpoints = [] } = useAllEndpoints({
    workspaceName,
  });

  const experiment = useMemo(() => {
    return configData?.experiments.find((exp) => exp.id === experimentId);
  }, [configData?.experiments, experimentId]);

  const selectedEndpoint = useMemo(() => {
    return endpoints.find((ep) => ep.id === selectedEndpointId);
  }, [endpoints, selectedEndpointId]);

  const handleCopyId = () => {
    navigator.clipboard.writeText(experimentId);
    toast({ description: "Experiment ID copied" });
  };

  const handleBack = () => {
    navigate({
      to: "/$workspaceName/experiments",
      params: { workspaceName },
    });
  };

  const isValidJson = useMemo(() => {
    try {
      JSON.parse(rawJson);
      return true;
    } catch {
      return false;
    }
  }, [rawJson]);

  const handleCall = useCallback(async () => {
    if (!selectedEndpoint) return;

    let body;
    try {
      body = JSON.parse(rawJson);
    } catch {
      setResult({ success: false, error: "Invalid JSON" });
      return;
    }

    setIsLoading(true);
    setResult(null);

    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        "X-Opik-Experiment-Id": experimentId,
      };

      if (selectedEndpoint.secret) {
        headers["Authorization"] = `Bearer ${selectedEndpoint.secret}`;
      }

      console.log("Calling endpoint:", selectedEndpoint.url);
      console.log("Headers:", headers);
      console.log("Body:", body);

      const response = await fetch(selectedEndpoint.url, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
      });

      const responseText = await response.text();
      console.log("Response status:", response.status);
      console.log("Response text:", responseText);

      let data = null;
      try {
        data = JSON.parse(responseText);
      } catch {
        data = { raw: responseText };
      }

      setResult({
        success: response.ok,
        status: response.status,
        data,
        error: response.ok ? undefined : `HTTP ${response.status}`,
      });
    } catch (err) {
      console.error("Fetch error:", err);
      setResult({
        success: false,
        error: err instanceof Error ? err.message : "Request failed",
      });
    } finally {
      setIsLoading(false);
    }
  }, [selectedEndpoint, rawJson, experimentId]);

  if (isPending) {
    return <Loader />;
  }

  if (isError || !experiment) {
    return (
      <PageBodyScrollContainer>
        <PageBodyStickyContainer
          direction="horizontal"
          limitWidth
          className="pt-6"
        >
          <Button variant="ghost" size="sm" onClick={handleBack}>
            <ArrowLeft className="mr-1.5 size-4" />
            Back to Experiments
          </Button>
          <DataTableNoData title="Experiment not found">
            The experiment with ID &quot;{experimentId}&quot; could not be found.
          </DataTableNoData>
        </PageBodyStickyContainer>
      </PageBodyScrollContainer>
    );
  }

  const headerCode = `X-Opik-Experiment-Id: ${experimentId}`;

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        direction="horizontal"
        limitWidth
        className="pt-6"
      >
        <Button variant="ghost" size="sm" onClick={handleBack} className="mb-4">
          <ArrowLeft className="mr-1.5 size-4" />
          Back to Experiments
        </Button>

        <div className="mb-6 flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="flex size-12 items-center justify-center rounded-lg bg-gradient-to-br from-purple-500 to-indigo-600">
              {experiment.isAb ? (
                <Split className="size-6 text-white" />
              ) : (
                <FlaskConical className="size-6 text-white" />
              )}
            </div>
            <div>
              <h1 className="comet-title-l flex items-center gap-2">
                {experiment.name || search.name || experimentId}
                {experiment.isAb && (
                  <Tag variant="blue" size="sm">
                    A/B Test
                  </Tag>
                )}
              </h1>
              <div className="mt-1 flex items-center gap-2 text-sm text-muted-slate">
                <code className="font-mono">{experimentId}</code>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={handleCopyId}
                >
                  <Copy className="size-3.5" />
                </Button>
              </div>
            </div>
          </div>
          <div className="text-sm text-muted-slate">
            Created {formatDate(experiment.createdAt)}
          </div>
        </div>

        {experiment.isAb && experiment.distribution && (
          <div className="mb-6 rounded-lg border bg-muted/30 p-4">
            <h2 className="mb-3 text-sm font-medium">Traffic Split</h2>
            <div className="flex gap-2">
              {Object.entries(experiment.distribution).map(([variant, weight]) => (
                <div
                  key={variant}
                  className="flex-1 rounded border bg-background p-3 text-center"
                >
                  <div
                    className={`text-2xl font-semibold ${
                      variant === "A" ? "text-blue-500" : "text-slate-500"
                    }`}
                  >
                    {weight}%
                  </div>
                  <div className="text-sm text-muted-slate">Variant {variant}</div>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="mb-6">
          <h2 className="mb-3 text-sm font-medium">
            Configuration Overrides ({experiment.overrides.length})
          </h2>
          <div className="space-y-2">
            {experiment.overrides.map((override) => (
              <div
                key={override.key}
                className="flex items-center justify-between rounded border bg-muted/30 px-4 py-3"
              >
                <span className="font-mono text-sm">{override.key}</span>
                <div className="flex items-center gap-2">
                  <Tag variant="gray" size="sm" className="capitalize">
                    {override.type}
                  </Tag>
                  <Tag variant="purple" size="sm" className="max-w-[300px] truncate">
                    {String(override.value)}
                  </Tag>
                </div>
              </div>
            ))}
            {experiment.overrides.length === 0 && (
              <div className="rounded border bg-muted/30 px-4 py-3 text-sm text-muted-slate">
                No overrides configured
              </div>
            )}
          </div>
        </div>

        <div className="mb-6 rounded-lg border p-4">
          <div className="mb-4 flex items-center gap-2">
            <Plug className="size-5 text-muted-slate" />
            <h2 className="text-sm font-medium">Call Endpoint</h2>
          </div>

          {endpoints.length === 0 ? (
            <div className="text-sm text-muted-slate">
              No endpoints configured. Create an endpoint in the Endpoints tab to call this experiment.
            </div>
          ) : (
            <div className="space-y-4">
              <div>
                <Label>Select Endpoint</Label>
                <Select value={selectedEndpointId} onValueChange={setSelectedEndpointId}>
                  <SelectTrigger className="mt-1.5">
                    <SelectValue placeholder="Choose an endpoint..." />
                  </SelectTrigger>
                  <SelectContent>
                    {endpoints.map((ep) => (
                      <SelectItem key={ep.id} value={ep.id}>
                        {ep.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {selectedEndpoint && (
                  <div className="mt-1.5 text-xs text-muted-slate">
                    <code>{selectedEndpoint.url}</code>
                  </div>
                )}
              </div>

              <div>
                <Label>Request Body (JSON)</Label>
                <Textarea
                  className="mt-1.5 min-h-[120px] font-mono text-sm"
                  value={rawJson}
                  onChange={(e) => setRawJson(e.target.value)}
                  placeholder='{"input": "Hello, world!"}'
                />
                {!isValidJson && rawJson.trim() && (
                  <p className="mt-1 text-sm text-destructive">Invalid JSON</p>
                )}
              </div>

              <div className="flex items-center gap-3">
                <Button
                  onClick={handleCall}
                  disabled={!selectedEndpointId || !isValidJson || isLoading}
                >
                  {isLoading ? (
                    <Loader2 className="mr-1.5 size-4 animate-spin" />
                  ) : (
                    <Play className="mr-1.5 size-4" />
                  )}
                  Call with Experiment
                </Button>
                <span className="text-xs text-muted-slate">
                  Header: <code>X-Opik-Experiment-Id: {experimentId}</code>
                </span>
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
                          {result.error} - {selectedEndpoint?.url}
                        </span>
                      </>
                    )}
                  </div>
                  {result.data ? (
                    <div className="max-h-[300px] overflow-auto rounded bg-muted p-2">
                      <pre className="text-xs whitespace-pre-wrap">
                        {JSON.stringify(result.data, null, 2)}
                      </pre>
                    </div>
                  ) : null}
                </div>
              )}
            </div>
          )}
        </div>

        <div className="mb-6">
          <h2 className="mb-3 text-sm font-medium">Manual Integration</h2>
          <div className="text-xs text-muted-slate mb-2">
            Pass this header when calling your agent endpoint:
          </div>
          <pre className="overflow-auto rounded border bg-slate-900 p-4 text-sm text-slate-100">
            <code>{headerCode}</code>
          </pre>
        </div>
      </PageBodyStickyContainer>
    </PageBodyScrollContainer>
  );
};

export default LiveExperimentPage;

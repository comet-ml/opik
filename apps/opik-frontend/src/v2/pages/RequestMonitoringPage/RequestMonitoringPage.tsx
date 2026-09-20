import { useEffect, useMemo, useState } from "react";
import {
  ArrowLeft,
  ArrowUpRight,
  Globe2,
  Network,
  RefreshCw,
} from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import useSystemMetricInstances from "@/api/system-metrics/useSystemMetricInstances";
import useSystemMetricSeries from "@/api/system-metrics/useSystemMetricSeries";
import useSystemMetricServiceSeries from "@/api/system-metrics/useSystemMetricServiceSeries";
import LineChart from "@/shared/Charts/LineChart/LineChart";
import Loader from "@/shared/Loader/Loader";
import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import { SystemMetricPoint } from "@/types/system-metrics";
import { Button } from "@/ui/button";
import { Card, CardContent } from "@/ui/card";
import { ChartConfig } from "@/ui/chart";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";

const DAY_MS = 24 * 60 * 60 * 1000;
const REFRESH_MS = 15_000;
const QPS_BUCKET_SECONDS = 60;

const qpsConfig: ChartConfig = {
  value: { label: "QPS", color: "var(--color-primary)" },
};

type RequestKind = "all" | "http" | "mcp";

type RequestRow = {
  id: string;
  timestamp: string;
  path: string;
  method: string;
  statusCode: number | null;
  duration: number;
  kind: Exclude<RequestKind, "all">;
  direction: string;
  traceId: string;
  threadId: string;
  mcpMethod: string;
};

type PathSummary = {
  key: string;
  path: string;
  method: string;
  kind: Exclude<RequestKind, "all">;
  requests: number;
  errorRate: number;
  averageDuration: number;
  p95Duration: number;
  lastSeen: string;
  latestTraceId: string;
  latestThreadId: string;
};

const attribute = (point: SystemMetricPoint, ...keys: string[]): string => {
  for (const key of keys) {
    const value = point.attributes[key];
    if (value) return value;
  }
  return "";
};

const toRequestRow = (point: SystemMetricPoint, index: number): RequestRow => {
  const path = attribute(point, "http.route", "url.path", "http.target") || "/";
  const mcpMethod = attribute(point, "mcp.method", "rpc.method");
  const explicitKind = attribute(point, "request.kind").toLowerCase();
  const kind =
    explicitKind === "mcp" || mcpMethod || path.toLowerCase().includes("mcp")
      ? "mcp"
      : "http";
  const rawStatusCode = attribute(
    point,
    "http.response.status_code",
    "http.status_code",
  );

  return {
    id: `${point.timestamp}-${index}-${path}`,
    timestamp: point.timestamp,
    path,
    method: attribute(point, "http.request.method", "http.method") || "—",
    statusCode: rawStatusCode ? Number(rawStatusCode) : null,
    duration: point.value,
    kind,
    direction: attribute(point, "direction") || "server",
    traceId: attribute(point, "trace.id", "trace_id"),
    threadId: attribute(point, "thread.id", "thread_id"),
    mcpMethod,
  };
};

const percentile = (values: number[], value: number) => {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[
    Math.min(Math.ceil(sorted.length * value) - 1, sorted.length - 1)
  ];
};

const summarizePaths = (rows: RequestRow[]): PathSummary[] => {
  const groups = new Map<string, RequestRow[]>();
  rows.forEach((row) => {
    const key = `${row.kind}:${row.method}:${row.path}`;
    groups.set(key, [...(groups.get(key) ?? []), row]);
  });

  return [...groups.entries()]
    .map(([key, requests]) => {
      const latest = requests.reduce((left, right) =>
        left.timestamp > right.timestamp ? left : right,
      );
      const errors = requests.filter(
        (request) => request.statusCode !== null && request.statusCode >= 400,
      ).length;
      const durations = requests.map((request) => request.duration);

      return {
        key,
        path: latest.path,
        method: latest.method,
        kind: latest.kind,
        requests: requests.length,
        errorRate: requests.length === 0 ? 0 : (errors / requests.length) * 100,
        averageDuration:
          durations.reduce((sum, duration) => sum + duration, 0) /
          durations.length,
        p95Duration: percentile(durations, 0.95),
        lastSeen: latest.timestamp,
        latestTraceId: latest.traceId,
        latestThreadId: latest.threadId,
      };
    })
    .sort((left, right) => right.lastSeen.localeCompare(left.lastSeen));
};

const toQpsData = (points: SystemMetricPoint[], path?: string) => {
  const buckets = new Map<number, number>();
  points.forEach((point) => {
    const pointPath =
      attribute(point, "http.route", "url.path", "http.target") || "/";
    if (path && pointPath !== path) return;
    const timestamp = new Date(point.timestamp).getTime();
    const bucket =
      Math.floor(timestamp / (QPS_BUCKET_SECONDS * 1_000)) *
      QPS_BUCKET_SECONDS *
      1_000;
    buckets.set(bucket, (buckets.get(bucket) ?? 0) + point.value);
  });

  return [...buckets.entries()]
    .sort(([left], [right]) => left - right)
    .map(([time, count]) => ({
      time: new Date(time).toISOString(),
      value: count / QPS_BUCKET_SECONDS,
    }));
};

const RequestMonitoringPage = () => {
  const projectId = useActiveProjectId()!;
  const workspaceName = useActiveWorkspaceName();
  const [selectedInstanceId, setSelectedInstanceId] = useState("");
  const [requestKind, setRequestKind] = useState<RequestKind>("all");
  const [selectedPath, setSelectedPath] = useQueryParam("path", StringParam, {
    updateType: "pushIn",
  });
  const [now, setNow] = useState(() => Date.now());
  const { data: instancesData, isPending: instancesPending } =
    useSystemMetricInstances(projectId);
  const instances = instancesData?.instances ?? [];
  const selectedInstance = instances.find(
    (instance) => instance.service_instance_id === selectedInstanceId,
  );
  const serviceInstanceIds = instances
    .filter(
      (instance) => instance.service_name === selectedInstance?.service_name,
    )
    .map((instance) => instance.service_instance_id);

  useEffect(() => {
    if (
      instances.length > 0 &&
      !instances.some(
        (instance) => instance.service_instance_id === selectedInstanceId,
      )
    ) {
      setSelectedInstanceId(instances[0].service_instance_id);
    }
  }, [instances, selectedInstanceId]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, []);

  const range = useMemo(
    () => ({
      from: new Date(now - DAY_MS).toISOString(),
      to: new Date(now).toISOString(),
    }),
    [now],
  );
  const requests = useSystemMetricSeries(
    {
      projectId,
      instanceId: selectedInstanceId,
      metricName: "agent.http.request.duration",
      ...range,
    },
    { enabled: Boolean(selectedInstanceId) },
  );
  const serviceRequests = useSystemMetricServiceSeries(
    {
      projectId,
      instanceIds: serviceInstanceIds,
      metricName: "agent.http.requests",
      ...range,
    },
    { enabled: serviceInstanceIds.length > 0 && Boolean(selectedPath) },
  );

  const rows = useMemo(
    () =>
      (requests.data?.points ?? [])
        .map(toRequestRow)
        .filter((row) => requestKind === "all" || row.kind === requestKind)
        .reverse(),
    [requestKind, requests.data?.points],
  );
  const pathSummaries = useMemo(() => summarizePaths(rows), [rows]);
  const selectedPathRows = useMemo(
    () => rows.filter((row) => row.path === selectedPath),
    [rows, selectedPath],
  );
  const serviceQpsData = useMemo(
    () => toQpsData(serviceRequests.data?.points ?? []),
    [serviceRequests.data?.points],
  );
  const pathQpsData = useMemo(
    () =>
      toQpsData(serviceRequests.data?.points ?? [], selectedPath ?? undefined),
    [selectedPath, serviceRequests.data?.points],
  );

  const projectBase = `/${encodeURIComponent(workspaceName)}/projects/${projectId}`;
  const traceUrl = (row: RequestRow) => {
    const params = new URLSearchParams({
      logsType: "traces",
      trace: row.traceId,
    });
    return `${projectBase}/logs?${params.toString()}`;
  };
  const threadUrl = (row: RequestRow) => {
    const params = new URLSearchParams({
      logsType: "threads",
      thread: row.threadId,
    });
    if (row.traceId) params.set("trace", row.traceId);
    return `${projectBase}/logs?${params.toString()}`;
  };

  return (
    <PageBodyScrollContainer>
      <div className="px-6 pb-8 pt-6">
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            {selectedPath && (
              <Button
                variant="minimal"
                size="xs"
                className="mb-2 -ml-2"
                onClick={() => setSelectedPath(undefined)}
              >
                <ArrowLeft className="mr-1 size-3.5" /> All paths
              </Button>
            )}
            <h1 className="comet-title-m">
              {selectedPath || "HTTP & MCP requests"}
            </h1>
            <p className="comet-body-s mt-1 text-light-slate">
              {selectedPath
                ? `${selectedInstance?.service_name || "Agent service"} QPS and correlated traces · last 24 hours`
                : "Request paths correlated with Opik traces and threads · last 24 hours"}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Select
              value={selectedInstanceId}
              onValueChange={setSelectedInstanceId}
              disabled={instances.length === 0}
            >
              <SelectTrigger className="w-[300px]">
                <SelectValue placeholder="Select an Agent instance" />
              </SelectTrigger>
              <SelectContent>
                {instances.map((instance) => (
                  <SelectItem
                    key={instance.service_instance_id}
                    value={instance.service_instance_id}
                    description={instance.service_instance_id}
                  >
                    {instance.service_name || instance.service_instance_id}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="Refresh requests"
              onClick={() => setNow(Date.now())}
            >
              <RefreshCw className="size-3.5" />
            </Button>
          </div>
        </div>

        {!selectedPath && (
          <Tabs
            value={requestKind}
            onValueChange={(value) => setRequestKind(value as RequestKind)}
            className="mb-4"
          >
            <TabsList variant="segmented-primary" className="w-fit">
              <TabsTrigger value="all" variant="segmented-primary" size="sm">
                All requests
              </TabsTrigger>
              <TabsTrigger value="http" variant="segmented-primary" size="sm">
                <Globe2 className="size-3.5" /> HTTP
              </TabsTrigger>
              <TabsTrigger value="mcp" variant="segmented-primary" size="sm">
                <Network className="size-3.5" /> MCP
              </TabsTrigger>
            </TabsList>
          </Tabs>
        )}

        {instancesPending || requests.isPending ? (
          <Loader />
        ) : instances.length === 0 ? (
          <Card>
            <CardContent className="flex min-h-64 flex-col items-center justify-center text-center">
              <Network className="mb-3 size-8 text-light-slate" />
              <div className="comet-body-accented">
                No Agent instances found
              </div>
              <div className="comet-body-s mt-1 text-light-slate">
                Start the Python system metrics reporter to collect requests.
              </div>
            </CardContent>
          </Card>
        ) : rows.length === 0 ? (
          <Card>
            <CardContent className="flex min-h-64 flex-col items-center justify-center text-center">
              <Network className="mb-3 size-8 text-light-slate" />
              <div className="comet-body-accented">No matching requests</div>
              <div className="comet-body-s mt-1 text-light-slate">
                Request events appear after record_request or record_mcp_request
                is called.
              </div>
            </CardContent>
          </Card>
        ) : selectedPath ? (
          <div className="space-y-4">
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
              <Card>
                <CardContent className="pt-5">
                  <div className="comet-body-accented">Service QPS</div>
                  <div className="comet-body-xs mt-1 text-light-slate">
                    {selectedInstance?.service_name || "Selected service"} ·{" "}
                    {serviceInstanceIds.length} active instance
                    {serviceInstanceIds.length === 1 ? "" : "s"}
                  </div>
                  {serviceRequests.isPending ? (
                    <Loader className="h-[220px] min-h-0" />
                  ) : serviceQpsData.length === 0 ? (
                    <div className="flex h-[220px] items-center justify-center text-sm text-light-slate">
                      No request-count samples
                    </div>
                  ) : (
                    <LineChart
                      chartId="request-service-qps"
                      config={qpsConfig}
                      data={serviceQpsData}
                      xTickFormatter={(value) =>
                        new Date(value).toLocaleTimeString([], {
                          hour: "2-digit",
                          minute: "2-digit",
                        })
                      }
                      customYTickFormatter={(value) => `${value.toFixed(2)}`}
                      showLegend={false}
                      className="mt-3 h-[205px] w-full"
                    />
                  )}
                </CardContent>
              </Card>
              <Card>
                <CardContent className="pt-5">
                  <div className="comet-body-accented">Path QPS</div>
                  <div className="comet-body-xs mt-1 truncate font-mono text-light-slate">
                    {selectedPath}
                  </div>
                  {serviceRequests.isPending ? (
                    <Loader className="h-[220px] min-h-0" />
                  ) : pathQpsData.length === 0 ? (
                    <div className="flex h-[220px] items-center justify-center text-sm text-light-slate">
                      No request-count samples
                    </div>
                  ) : (
                    <LineChart
                      chartId="request-path-qps"
                      config={qpsConfig}
                      data={pathQpsData}
                      xTickFormatter={(value) =>
                        new Date(value).toLocaleTimeString([], {
                          hour: "2-digit",
                          minute: "2-digit",
                        })
                      }
                      customYTickFormatter={(value) => `${value.toFixed(2)}`}
                      showLegend={false}
                      className="mt-3 h-[205px] w-full"
                    />
                  )}
                </CardContent>
              </Card>
            </div>

            <Card className="overflow-hidden">
              <CardContent className="p-0">
                <div className="border-b px-4 py-3">
                  <div className="comet-body-accented">Correlated requests</div>
                  <div className="comet-body-xs mt-1 text-light-slate">
                    Click a Trace ID or Thread ID to open it in Opik Logs.
                  </div>
                </div>
                <div className="overflow-x-auto">
                  <Table className="min-w-[1000px]">
                    <TableHeader>
                      <TableRow>
                        {[
                          "Time",
                          "Type",
                          "Method",
                          "Status",
                          "Duration",
                          "Trace ID",
                          "Thread ID",
                        ].map((heading) => (
                          <TableHead key={heading}>
                            <div className="px-3 py-2.5">{heading}</div>
                          </TableHead>
                        ))}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {selectedPathRows.map((row) => (
                        <TableRow key={row.id}>
                          <TableCell>
                            <div className="whitespace-nowrap px-3 py-3 text-light-slate">
                              {new Date(row.timestamp).toLocaleString()}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="px-3 py-3">
                              <span className="rounded bg-primary-100 px-2 py-1 text-xs font-medium uppercase text-primary">
                                {row.kind}
                              </span>
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="px-3 py-3 font-mono text-xs">
                              {row.method}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div
                              className={
                                row.statusCode !== null && row.statusCode >= 400
                                  ? "px-3 py-3 text-destructive"
                                  : "px-3 py-3"
                              }
                            >
                              {row.statusCode ?? "—"}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="whitespace-nowrap px-3 py-3">
                              {(row.duration * 1000).toFixed(1)} ms
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="px-3 py-3">
                              {row.traceId ? (
                                <a
                                  href={traceUrl(row)}
                                  className="inline-flex max-w-48 items-center gap-1 font-mono text-xs text-primary hover:underline"
                                  title={row.traceId}
                                >
                                  <span className="truncate">
                                    {row.traceId}
                                  </span>
                                  <ArrowUpRight className="size-3 shrink-0" />
                                </a>
                              ) : (
                                <span className="text-xs text-light-slate">
                                  Unlinked
                                </span>
                              )}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="px-3 py-3">
                              {row.threadId ? (
                                <a
                                  href={threadUrl(row)}
                                  className="block max-w-48 truncate font-mono text-xs text-primary hover:underline"
                                  title={row.threadId}
                                >
                                  {row.threadId}
                                </a>
                              ) : (
                                <span className="text-xs text-light-slate">
                                  —
                                </span>
                              )}
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </CardContent>
            </Card>
          </div>
        ) : (
          <Card className="overflow-hidden">
            <CardContent className="p-0">
              <div className="overflow-x-auto">
                <Table className="min-w-[1000px]">
                  <TableHeader>
                    <TableRow>
                      {[
                        "Type",
                        "Method",
                        "Path",
                        "Requests",
                        "Error rate",
                        "Avg latency",
                        "P95 latency",
                        "Latest Trace / Thread",
                      ].map((heading) => (
                        <TableHead key={heading}>
                          <div className="px-3 py-2.5">{heading}</div>
                        </TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {pathSummaries.map((summary) => (
                      <TableRow key={summary.key}>
                        <TableCell>
                          <div className="px-3 py-3">
                            <span className="rounded bg-primary-100 px-2 py-1 text-xs font-medium uppercase text-primary">
                              {summary.kind}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="px-3 py-3 font-mono text-xs">
                            {summary.method}
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="px-3 py-3">
                            <button
                              type="button"
                              className="inline-flex max-w-full items-center gap-1 font-mono text-xs text-primary hover:underline"
                              onClick={() => setSelectedPath(summary.path)}
                            >
                              <span className="truncate">{summary.path}</span>
                              <ArrowUpRight className="size-3 shrink-0" />
                            </button>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="px-3 py-3">{summary.requests}</div>
                        </TableCell>
                        <TableCell>
                          <div
                            className={
                              summary.errorRate > 0
                                ? "px-3 py-3 text-destructive"
                                : "px-3 py-3"
                            }
                          >
                            {summary.errorRate.toFixed(1)}%
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="whitespace-nowrap px-3 py-3">
                            {(summary.averageDuration * 1000).toFixed(1)} ms
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="whitespace-nowrap px-3 py-3">
                            {(summary.p95Duration * 1000).toFixed(1)} ms
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="px-3 py-3 text-xs">
                            <div
                              className="max-w-40 truncate font-mono"
                              title={summary.latestTraceId}
                            >
                              {summary.latestTraceId || "Unlinked"}
                            </div>
                            <div
                              className="mt-1 max-w-40 truncate font-mono text-light-slate"
                              title={summary.latestThreadId}
                            >
                              {summary.latestThreadId || "No thread"}
                            </div>
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              {requests.data?.truncated && (
                <div className="border-t px-4 py-3 text-xs text-light-slate">
                  The result reached the configured query limit. Narrow the time
                  range to see every request.
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </PageBodyScrollContainer>
  );
};

export default RequestMonitoringPage;

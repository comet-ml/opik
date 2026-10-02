import React, { useEffect, useMemo, useState } from "react";
import { Activity, Cpu, Database, Gauge, MemoryStick } from "lucide-react";

import useSystemMetricInstances from "@/api/system-metrics/useSystemMetricInstances";
import useSystemMetricSeries from "@/api/system-metrics/useSystemMetricSeries";
import LineChart from "@/shared/Charts/LineChart/LineChart";
import Loader from "@/shared/Loader/Loader";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import { useActiveProjectId } from "@/store/AppStore";
import { Card, CardContent, CardHeader, CardTitle } from "@/ui/card";
import { ChartConfig } from "@/ui/chart";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { SystemMetricSeries } from "@/types/system-metrics";

const HOUR_MS = 60 * 60 * 1000;
const REFRESH_MS = 15_000;

const percentConfig: ChartConfig = {
  value: { label: "Utilization", color: "var(--color-primary)" },
};
const memoryConfig: ChartConfig = {
  value: { label: "RSS", color: "var(--color-green)" },
};
const filesystemConfig: ChartConfig = {
  value: { label: "Used", color: "var(--color-orange)" },
};
const requestConfig: ChartConfig = {
  value: { label: "Duration", color: "var(--color-primary)" },
};

const formatBytes = (value: number) => {
  if (!Number.isFinite(value) || value === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(
    Math.floor(Math.log(Math.abs(value)) / Math.log(1024)),
    units.length - 1,
  );
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
};

type MetricCardProps = {
  title: string;
  description: string;
  icon: React.ReactNode;
  series?: SystemMetricSeries;
  isPending: boolean;
  chartId: string;
  config: ChartConfig;
  filter?: (point: SystemMetricSeries["points"][number]) => boolean;
  valueFormatter?: (value: number) => string;
};

const MetricCard: React.FC<MetricCardProps> = ({
  title,
  description,
  icon,
  series,
  isPending,
  chartId,
  config,
  filter,
  valueFormatter,
}) => {
  const points = useMemo(
    () => (series?.points ?? []).filter((point) => !filter || filter(point)),
    [filter, series?.points],
  );
  const data = useMemo(
    () => points.map((point) => ({ time: point.timestamp, value: point.value })),
    [points],
  );
  const latest = points.at(-1)?.value;

  return (
    <Card className="min-h-[300px]">
      <CardHeader className="flex-row items-start justify-between space-y-0">
        <div>
          <CardTitle>{title}</CardTitle>
          <div className="comet-body-s mt-1 text-light-slate">{description}</div>
        </div>
        <div className="text-light-slate">{icon}</div>
      </CardHeader>
      <CardContent>
        {isPending ? (
          <Loader className="h-[210px] min-h-0" />
        ) : data.length === 0 ? (
          <div className="flex h-[210px] items-center justify-center text-sm text-light-slate">
            No samples in the last hour
          </div>
        ) : (
          <>
            <div className="comet-title-s mb-3">
              {latest === undefined
                ? "—"
                : valueFormatter
                  ? valueFormatter(latest)
                  : latest.toFixed(2)}
            </div>
            <LineChart
              chartId={chartId}
              config={config}
              data={data}
              xTickFormatter={(value) =>
                new Date(value).toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                })
              }
              customYTickFormatter={valueFormatter}
              showLegend={false}
              className="h-[175px] w-full"
            />
          </>
        )}
      </CardContent>
    </Card>
  );
};

const SystemMetricsPage = () => {
  const projectId = useActiveProjectId()!;
  const [selectedInstanceId, setSelectedInstanceId] = useState("");
  const [now, setNow] = useState(() => Date.now());
  const { data: instancesData, isPending: instancesPending } =
    useSystemMetricInstances(projectId);
  const instances = instancesData?.instances ?? [];

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
      from: new Date(now - HOUR_MS).toISOString(),
      to: new Date(now + 1_000).toISOString(),
    }),
    [now],
  );
  const enabled = Boolean(selectedInstanceId);
  const query = (metricName: string) => ({
    projectId,
    instanceId: selectedInstanceId,
    metricName,
    ...range,
  });

  const cpu = useSystemMetricSeries(query("agent.process.cpu.utilization"), {
    enabled,
  });
  const memory = useSystemMetricSeries(query("agent.process.memory"), {
    enabled,
  });
  const memoryPercent = useSystemMetricSeries(
    query("agent.process.memory.utilization"),
    { enabled },
  );
  const filesystem = useSystemMetricSeries(query("agent.filesystem.usage"), {
    enabled,
  });
  const requestDuration = useSystemMetricSeries(
    query("agent.http.request.duration"),
    { enabled },
  );

  return (
    <PageBodyScrollContainer>
      <div className="px-6 pb-8 pt-6">
        <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="comet-title-m">System monitoring</h1>
            <p className="comet-body-s mt-1 text-light-slate">
              Application CPU, memory, filesystem and request telemetry · last hour
            </p>
          </div>
          <Select
            value={selectedInstanceId}
            onValueChange={setSelectedInstanceId}
            disabled={instances.length === 0}
          >
            <SelectTrigger className="w-[320px]">
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
        </div>

        {instancesPending ? (
          <Loader />
        ) : instances.length === 0 ? (
          <Card>
            <CardContent className="flex min-h-64 flex-col items-center justify-center text-center">
              <Gauge className="mb-3 size-8 text-light-slate" />
              <div className="comet-body-accented">No Agent metrics received</div>
              <div className="comet-body-s mt-1 max-w-xl text-light-slate">
                Start the Python system metrics reporter for this project. Active instances appear here automatically.
              </div>
            </CardContent>
          </Card>
        ) : (
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
            <MetricCard
              title="CPU utilization"
              description="Current Python Agent process"
              icon={<Cpu className="size-5" />}
              series={cpu.data}
              isPending={cpu.isPending}
              chartId="system-cpu-utilization"
              config={percentConfig}
              valueFormatter={(value) => `${value.toFixed(1)}%`}
            />
            <MetricCard
              title="RSS memory"
              description="Resident memory held by the Agent process"
              icon={<MemoryStick className="size-5" />}
              series={memory.data}
              isPending={memory.isPending}
              chartId="system-memory-rss"
              config={memoryConfig}
              valueFormatter={formatBytes}
            />
            <MetricCard
              title="Memory utilization"
              description="Process memory as a percentage of available memory"
              icon={<Activity className="size-5" />}
              series={memoryPercent.data}
              isPending={memoryPercent.isPending}
              chartId="system-memory-utilization"
              config={percentConfig}
              valueFormatter={(value) => `${value.toFixed(2)}%`}
            />
            <MetricCard
              title="Filesystem used"
              description="Container-visible filesystem consumption"
              icon={<Database className="size-5" />}
              series={filesystem.data}
              isPending={filesystem.isPending}
              chartId="system-filesystem-used"
              config={filesystemConfig}
              filter={(point) => point.attributes.state === "used"}
              valueFormatter={formatBytes}
            />
            <MetricCard
              title="Request duration"
              description="Agent HTTP server and client request latency"
              icon={<Gauge className="size-5" />}
              series={requestDuration.data}
              isPending={requestDuration.isPending}
              chartId="system-request-duration"
              config={requestConfig}
              valueFormatter={(value) => `${value.toFixed(3)} s`}
            />
          </div>
        )}
      </div>
    </PageBodyScrollContainer>
  );
};

export default SystemMetricsPage;

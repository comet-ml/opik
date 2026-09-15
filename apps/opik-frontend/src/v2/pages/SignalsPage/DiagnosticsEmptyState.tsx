import React from "react";
import { ArrowUpRight, Play } from "lucide-react";
import { buildDocsUrl } from "@/v2/lib/utils";
import { Button } from "@/ui/button";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import OutOfCreditsButton from "@/v2/pages/SignalsPage/OutOfCreditsButton";
import sampleIssuesLightUrl from "/images/diagnostics-sample-issues-light.svg";
import sampleIssuesDarkUrl from "/images/diagnostics-sample-issues-dark.svg";

const DIAGNOSTICS_DOCS_URL = buildDocsUrl("/tracing/diagnostics");
// Matches the backend's threshold; the run fires when a project crosses it.
const TRACE_THRESHOLD = 100;

type DiagnosticsEmptyStateProps = {
  awaitsAutoFirstRun: boolean;
  traceCount: number;
  isOutOfCredits: boolean;
  canConfigure: boolean;
  onRun: () => void;
  isRunPending: boolean;
};

const DiagnosticsEmptyState: React.FC<DiagnosticsEmptyStateProps> = ({
  awaitsAutoFirstRun,
  traceCount,
  isOutOfCredits,
  canConfigure,
  onRun,
  isRunPending,
}) => {
  const { themeMode } = useTheme();
  const sampleIssuesUrl =
    themeMode === THEME_MODE.DARK ? sampleIssuesDarkUrl : sampleIssuesLightUrl;

  // The state stays up past the threshold until the backend's first run lands, so the
  // counter is capped — "500/100" would read as a bug.
  const reached = Math.min(traceCount, TRACE_THRESHOLD);
  const progress = (reached / TRACE_THRESHOLD) * 100;

  return (
    <div className="flex min-h-[70vh] items-center justify-center px-8 py-10">
      <div className="flex w-full max-w-[480px] flex-col items-center gap-6">
        <div className="flex flex-col items-center gap-3 text-center">
          <h2 className="text-base font-medium tracking-normal text-foreground-secondary">
            Find what&apos;s breaking in your agent
          </h2>
          <p className="comet-body-s text-muted-slate">
            Use diagnostics to read your traces and return non-error issues
            ranked by impact.
          </p>
        </div>

        <img
          src={sampleIssuesUrl}
          alt=""
          aria-hidden="true"
          className="my-4 h-[206px] w-auto max-w-full shrink-0 select-none"
        />

        {awaitsAutoFirstRun ? (
          <>
            <div className="flex w-full flex-col gap-2">
              <div className="flex items-center justify-between gap-2">
                <span className="comet-body-xs text-muted-slate">
                  Traces in the last 7 days
                </span>
                <span className="comet-body-xs text-muted-slate">
                  {reached.toLocaleString()}/{TRACE_THRESHOLD}
                </span>
              </div>
              <div
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={TRACE_THRESHOLD}
                aria-valuenow={reached}
                aria-label="Traces in the last 7 days"
                className="h-1.5 w-full overflow-hidden rounded-md bg-primary/20"
              >
                <div
                  className="h-full rounded-md bg-primary"
                  style={{ width: `${progress}%` }}
                />
              </div>
            </div>

            <p className="comet-body-xs text-center text-muted-slate">
              Diagnostics runs best on {TRACE_THRESHOLD}+ traces. Once you reach{" "}
              {TRACE_THRESHOLD}, your first diagnostic runs automatically for
              free.
            </p>

            <Button variant="outline" size="sm" asChild>
              <a
                href={DIAGNOSTICS_DOCS_URL}
                target="_blank"
                rel="noopener noreferrer"
              >
                Read docs
                <ArrowUpRight className="ml-1.5 size-3.5" />
              </a>
            </Button>
          </>
        ) : (
          canConfigure &&
          (isOutOfCredits ? (
            <OutOfCreditsButton
              large
              label="Add Ollie credits to run diagnostic"
              description="You need Ollie credits to run diagnostic. Ollie credits are shared across AI features in Opik — a workspace admin can add more."
            />
          ) : (
            <Button onClick={onRun} disabled={isRunPending}>
              <Play className="mr-2 size-4" />
              Run your first diagnostic
            </Button>
          ))
        )}
      </div>
    </div>
  );
};

export default DiagnosticsEmptyState;

import React, { useState } from "react";
import { AlertCircle, ChevronLeft, ChevronRight, Sparkles } from "lucide-react";
import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";
import Loader from "@/shared/Loader/Loader";
import { KitPack } from "./kit/loadKit";
import SandboxedView from "./SandboxedView";

export type SandboxedViewSample = {
  key: string;
  label: string;
  data: unknown;
  fallback: React.ReactNode;
};

type SandboxedViewPreviewProps = {
  samples: SandboxedViewSample[];
  sampleIndex: number;
  onSampleIndexChange: (index: number) => void;
  html: string;
  kitVersion: number;
  packs: KitPack[];
  title: string;
  failureDescription: string;
  onFixError?: (message: string) => void;
};

const SandboxedViewPreview: React.FC<SandboxedViewPreviewProps> = ({
  samples,
  sampleIndex,
  onSampleIndexChange,
  html,
  kitVersion,
  packs,
  title,
  failureDescription,
  onFixError,
}) => {
  const sample = samples[sampleIndex];
  const [failure, setFailure] = useState<{
    key: string;
    html: string;
    message: string;
  }>();
  const hasView = Boolean(html.trim());
  const error =
    hasView && failure && failure.key === sample?.key && failure.html === html
      ? failure.message
      : undefined;

  if (!sample) return null;

  const renderBody = () => {
    if (sample.data === undefined) return <Loader />;
    if (!hasView) return sample.fallback;
    return (
      <SandboxedView
        html={html}
        data={sample.data}
        kitVersion={kitVersion}
        packs={packs}
        title={title}
        onError={(message) => setFailure({ key: sample.key, html, message })}
      />
    );
  };

  return (
    <div className="overflow-hidden rounded-md border border-border">
      <div className="flex h-9 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
        <span className="comet-body-xs-accented truncate">{sample.label}</span>
        {samples.length > 1 && (
          <div className="flex shrink-0 items-center gap-1">
            <Button
              variant="minimal"
              size="icon-2xs"
              aria-label="Previous sample"
              disabled={sampleIndex === 0}
              onClick={() => onSampleIndexChange(sampleIndex - 1)}
            >
              <ChevronLeft />
            </Button>
            <span className="comet-body-xs text-muted-slate">
              {sampleIndex + 1} of {samples.length}
            </span>
            <Button
              variant="minimal"
              size="icon-2xs"
              aria-label="Next sample"
              disabled={sampleIndex >= samples.length - 1}
              onClick={() => onSampleIndexChange(sampleIndex + 1)}
            >
              <ChevronRight />
            </Button>
          </div>
        )}
      </div>
      {error && (
        <Alert
          variant="destructive"
          size="sm"
          className="rounded-none border-x-0 border-t-0"
        >
          <AlertCircle />
          <AlertDescription className="flex items-center justify-between gap-2">
            <span>
              {failureDescription} Error: {error}
            </span>
            {onFixError && (
              <Button
                variant="outline"
                size="2xs"
                className="shrink-0"
                onClick={() => onFixError(error)}
              >
                <Sparkles className="mr-1 size-3" />
                Fix with Ollie
              </Button>
            )}
          </AlertDescription>
        </Alert>
      )}
      <div className="p-3">{renderBody()}</div>
    </div>
  );
};

export default SandboxedViewPreview;

import React, { useState } from "react";
import { AlertCircle, ChevronLeft, ChevronRight, Sparkles } from "lucide-react";
import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import Loader from "@/shared/Loader/Loader";
import { KitPack } from "./kit/loadKit";
import SandboxedView from "./SandboxedView";

export type SandboxedViewSample = {
  key: string;
  label: React.ReactNode;
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
    <div className="flex flex-col gap-2">
      <div className="flex h-6 items-center justify-between gap-2 px-0.5">
        {sample.label}
        {samples.length > 1 && (
          <div className="flex shrink-0 items-center gap-0.5 text-muted-slate">
            <Button
              type="button"
              variant="minimal"
              size="icon-3xs"
              className="text-muted-slate"
              aria-label="Previous sample"
              disabled={sampleIndex === 0}
              onClick={() => onSampleIndexChange(sampleIndex - 1)}
            >
              <ChevronLeft />
            </Button>
            <span className="comet-body-xs">
              {sampleIndex + 1} of {samples.length}
            </span>
            <Button
              type="button"
              variant="minimal"
              size="icon-3xs"
              className="text-muted-slate"
              aria-label="Next sample"
              disabled={sampleIndex >= samples.length - 1}
              onClick={() => onSampleIndexChange(sampleIndex + 1)}
            >
              <ChevronRight />
            </Button>
          </div>
        )}
      </div>
      <Separator className="my-1" />
      {error && (
        <Alert variant="destructive" size="sm">
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
      {renderBody()}
    </div>
  );
};

export default SandboxedViewPreview;

import React, { useMemo } from "react";
import { Thread, Trace } from "@/types/traces";
import SandboxedView, {
  SandboxedViewErrorKind,
} from "@/shared/SandboxedView/SandboxedView";
import {
  ANNOTATOR_VIEW_PACKS,
  toAnnotatorThreadViewData,
} from "./annotatorViewData";

type SandboxedAnnotatorThreadViewProps = {
  html: string;
  kitVersion: number;
  thread: Thread;
  traces: Trace[];
  onError?: (message: string, kind: SandboxedViewErrorKind) => void;
};

const SandboxedAnnotatorThreadView: React.FC<
  SandboxedAnnotatorThreadViewProps
> = ({ html, kitVersion, thread, traces, onError }) => {
  const data = useMemo(
    () => toAnnotatorThreadViewData(thread, traces),
    [thread, traces],
  );

  return (
    <SandboxedView
      html={html}
      data={data}
      kitVersion={kitVersion}
      packs={ANNOTATOR_VIEW_PACKS}
      title="Annotator view"
      onError={onError}
    />
  );
};

export default SandboxedAnnotatorThreadView;

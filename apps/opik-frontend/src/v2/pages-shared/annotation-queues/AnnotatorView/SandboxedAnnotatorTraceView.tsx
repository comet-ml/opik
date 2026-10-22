import React, { useMemo } from "react";
import { Trace } from "@/types/traces";
import SandboxedView, {
  SandboxedViewErrorKind,
} from "@/shared/SandboxedView/SandboxedView";
import {
  ANNOTATOR_VIEW_PACKS,
  toAnnotatorTraceViewData,
} from "./annotatorViewData";

type SandboxedAnnotatorTraceViewProps = {
  html: string;
  kitVersion: number;
  trace: Trace;
  onError?: (message: string, kind: SandboxedViewErrorKind) => void;
};

const SandboxedAnnotatorTraceView: React.FC<
  SandboxedAnnotatorTraceViewProps
> = ({ html, kitVersion, trace, onError }) => {
  const data = useMemo(() => toAnnotatorTraceViewData(trace), [trace]);

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

export default SandboxedAnnotatorTraceView;

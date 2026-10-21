import React from "react";
import FieldsSection from "./FieldsSection";
import MessagesSection from "./MessagesSection";
import AnnotatorViewPreview from "./AnnotatorViewPreview";
import useAnnotatorPreviewSamples from "./useAnnotatorPreviewSamples";
import { AnnotatorViewDraft } from "./useAnnotatorViewDraft";

type AnnotatorViewTabProps = {
  projectId: string;
  queueId?: string;
  isThreadScope: boolean;
  value: AnnotatorViewDraft;
  onChange: (value: AnnotatorViewDraft) => void;
};

const AnnotatorViewTab: React.FC<AnnotatorViewTabProps> = ({
  projectId,
  queueId,
  isThreadScope,
  value,
  onChange,
}) => {
  const { samples, sample, sampleIndex, setSampleIndex, isPending } =
    useAnnotatorPreviewSamples({ projectId, queueId, isThreadScope });

  return (
    <div className="flex flex-col gap-4">
      <MessagesSection
        defaultOpen={isThreadScope}
        sample={sample}
        isSamplePending={isPending}
        value={value}
        onChange={onChange}
      />
      {!isThreadScope && (
        <FieldsSection
          sample={sample}
          isSamplePending={isPending}
          value={value}
          onChange={onChange}
        />
      )}
      <AnnotatorViewPreview
        samples={samples}
        sample={sample}
        sampleIndex={sampleIndex}
        onSampleIndexChange={setSampleIndex}
        isThreadScope={isThreadScope}
        value={value}
      />
    </div>
  );
};

export default AnnotatorViewTab;

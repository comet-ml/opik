import React, { useState } from "react";
import isObject from "lodash/isObject";
import { ChevronDown } from "lucide-react";
import { cn, getJSONPathRootKey, getValueByJSONPath } from "@/lib/utils";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import { AnnotatorViewField } from "@/types/annotation-queues";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import AttachmentsList from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import CodeBlock from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlock";
import AnnotatorFieldValue from "./AnnotatorFieldValue";
import {
  AnnotatorTraceSource,
  getFieldKind,
  getFieldScores,
} from "./annotatorViewConfig";

type AnnotatorFieldsViewProps = {
  fields: AnnotatorViewField[];
  source: AnnotatorTraceSource;
  media: UnifiedMediaItem[];
  feedbackScores?: TraceFeedbackScore[];
  preserveKeyPrefix?: string;
};

const AnnotatorFieldBlock: React.FC<{
  label: string;
  children: React.ReactNode;
}> = ({ label, children }) => {
  const [isOpen, setIsOpen] = useState(true);

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border border-border bg-soft-background",
        isOpen && "pb-2",
      )}
    >
      <button
        type="button"
        aria-expanded={isOpen}
        onClick={() => setIsOpen(!isOpen)}
        className={cn(
          "flex h-8 w-full items-center gap-1 border-b px-2 text-left hover:opacity-80",
          isOpen ? "border-border" : "border-transparent",
        )}
      >
        <ChevronDown
          className={cn(
            "size-3.5 shrink-0 text-light-slate transition-transform duration-200",
            !isOpen && "-rotate-90",
          )}
        />
        <span className="comet-body-xs-accented truncate text-muted-slate">
          {label}
        </span>
      </button>
      {isOpen && <div className="px-3 pt-2">{children}</div>}
    </div>
  );
};

const PRETTIFY_ROOTS = ["input", "output"] as const;

const AnnotatorFieldsView: React.FC<AnnotatorFieldsViewProps> = ({
  fields,
  source,
  media,
  feedbackScores,
  preserveKeyPrefix,
}) => {
  const renderField = (field: AnnotatorViewField) => {
    const kind = getFieldKind(field.path);

    if (kind === "attachments") {
      return media.length ? (
        <AttachmentsList media={media} title={field.label} />
      ) : null;
    }

    if (kind === "feedback_scores") {
      const scores = getFieldScores(field.path, feedbackScores);
      return (
        <AnnotatorFieldBlock label={field.label}>
          {scores.length ? (
            <div className="flex flex-wrap gap-1.5">
              {scores.map((score) => (
                <FeedbackScoreTag
                  key={score.name}
                  label={score.name}
                  value={score.value}
                  reason={score.reason}
                  lastUpdatedAt={score.last_updated_at}
                  lastUpdatedBy={score.last_updated_by}
                  valueByAuthor={score.value_by_author}
                  category={score.category_name}
                />
              ))}
            </div>
          ) : (
            <AnnotatorFieldValue value={undefined} />
          )}
        </AnnotatorFieldBlock>
      );
    }

    const value = getValueByJSONPath(
      { ...source, metadata: source.metadata ?? {} },
      field.path,
    );
    if (isObject(value)) {
      const root = getJSONPathRootKey(field.path);
      const fieldType = PRETTIFY_ROOTS.find((type) => type === root);
      return (
        <CodeBlock
          title={field.label}
          data={value}
          prettifyConfig={fieldType && { fieldType }}
          preserveKey={
            preserveKeyPrefix && `${preserveKeyPrefix}-${field.path}`
          }
          withSearch
          defaultOpen={field.path !== "metadata"}
        />
      );
    }

    return (
      <AnnotatorFieldBlock label={field.label}>
        <AnnotatorFieldValue value={value} />
      </AnnotatorFieldBlock>
    );
  };

  return (
    <div className="grid grid-cols-2 gap-3">
      {fields.map((field, index) => {
        const content = renderField(field);
        if (!content) return null;
        return (
          <div
            key={`${index}-${field.path}`}
            className={cn(
              "min-w-0",
              field.half_width ? "col-span-1" : "col-span-2",
            )}
          >
            {content}
          </div>
        );
      })}
    </div>
  );
};

export default AnnotatorFieldsView;

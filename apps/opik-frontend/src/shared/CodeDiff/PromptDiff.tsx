import React, { useMemo } from "react";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import {
  OpenAIMessage,
  extractMessageContent,
  extractPromptData,
  NamedPrompts,
} from "@/lib/prompt";
import TextDiff from "./TextDiff";
import { Tag } from "@/ui/tag";

/**
 * "default" keeps the original muted cards with role tags. "panel" is the
 * trials-table diff popover style: primary-foreground cards with a plain
 * muted role label, changes communicated by the inline highlight alone.
 */
type PromptDiffVariant = "default" | "panel";

type PromptDiffProps = {
  baseline: unknown;
  current: unknown;
  variant?: PromptDiffVariant;
};

const MessagesDiff: React.FC<{
  baseline: OpenAIMessage[];
  current: OpenAIMessage[];
  variant: PromptDiffVariant;
}> = ({ baseline, current, variant }) => {
  const baselineByRole = useMemo(() => {
    const map = new Map<string, string>();
    baseline.forEach((msg) => {
      const existing = map.get(msg.role) || "";
      map.set(
        msg.role,
        existing + (existing ? "\n" : "") + extractMessageContent(msg.content),
      );
    });
    return map;
  }, [baseline]);

  const currentByRole = useMemo(() => {
    const map = new Map<string, string>();
    current.forEach((msg) => {
      const existing = map.get(msg.role) || "";
      map.set(
        msg.role,
        existing + (existing ? "\n" : "") + extractMessageContent(msg.content),
      );
    });
    return map;
  }, [current]);

  const allRoles = useMemo(() => {
    const roles = new Set([...baselineByRole.keys(), ...currentByRole.keys()]);
    const roleOrder = ["system", "user", "assistant"];
    return Array.from(roles).sort((a, b) => {
      const aIndex = roleOrder.indexOf(a);
      const bIndex = roleOrder.indexOf(b);
      if (aIndex === -1 && bIndex === -1) return a.localeCompare(b);
      if (aIndex === -1) return 1;
      if (bIndex === -1) return -1;
      return aIndex - bIndex;
    });
  }, [baselineByRole, currentByRole]);

  return (
    <div className="flex flex-col gap-2">
      {allRoles.map((role) => {
        const baseContent = baselineByRole.get(role) || "";
        const currContent = currentByRole.get(role) || "";
        const hasChanged = baseContent !== currContent;
        const roleName =
          LLM_MESSAGE_ROLE_NAME_MAP[
            role as keyof typeof LLM_MESSAGE_ROLE_NAME_MAP
          ] || role;

        return (
          <div
            key={role}
            className={
              variant === "panel"
                ? "rounded-md border bg-primary-foreground px-3 py-2"
                : "rounded-md border bg-muted/30 p-3"
            }
          >
            <div className="mb-2 flex items-center gap-2">
              {variant === "panel" ? (
                <span className="comet-body-xs-accented capitalize text-muted-slate">
                  {roleName}
                </span>
              ) : (
                <Tag variant="gray" size="sm" className="capitalize">
                  {roleName}
                </Tag>
              )}
              {variant !== "panel" &&
                hasChanged &&
                baseContent &&
                currContent && (
                  <Tag variant="orange" size="sm">
                    Changed
                  </Tag>
                )}
              {!baseContent && currContent && (
                <Tag variant="green" size="sm">
                  Added
                </Tag>
              )}
              {baseContent && !currContent && (
                <Tag variant="red" size="sm">
                  Removed
                </Tag>
              )}
            </div>
            <div className="comet-body-s whitespace-pre-wrap break-words">
              {hasChanged ? (
                <TextDiff
                  content1={baseContent}
                  content2={currContent}
                  mode="words"
                />
              ) : (
                <span>{currContent}</span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};

const NamedPromptsDiff: React.FC<{
  baseline: NamedPrompts;
  current: NamedPrompts;
  variant: PromptDiffVariant;
}> = ({ baseline, current, variant }) => {
  const names = useMemo(() => {
    const s = new Set([...Object.keys(baseline), ...Object.keys(current)]);
    return Array.from(s).sort();
  }, [baseline, current]);

  return (
    <div className="flex flex-col gap-4">
      {names.map((name) => (
        <div key={name}>
          <p className="comet-body-s mb-1 text-muted-slate">{name}</p>
          <MessagesDiff
            baseline={baseline[name] ?? []}
            current={current[name] ?? []}
            variant={variant}
          />
        </div>
      ))}
    </div>
  );
};

const PromptDiff: React.FunctionComponent<PromptDiffProps> = ({
  baseline,
  current,
  variant = "default",
}) => {
  const baselineExtracted = useMemo(
    () => extractPromptData(baseline),
    [baseline],
  );
  const currentExtracted = useMemo(() => extractPromptData(current), [current]);

  if (!baselineExtracted || !currentExtracted) {
    const baseText =
      typeof baseline === "string"
        ? baseline
        : JSON.stringify(baseline, null, 2) ?? "";
    const currText =
      typeof current === "string"
        ? current
        : JSON.stringify(current, null, 2) ?? "";

    return <TextDiff content1={baseText} content2={currText} mode="words" />;
  }

  if (baselineExtracted.type !== currentExtracted.type) {
    const baseText = JSON.stringify(baseline, null, 2) ?? "";
    const currText = JSON.stringify(current, null, 2) ?? "";

    return <TextDiff content1={baseText} content2={currText} mode="words" />;
  }

  if (
    baselineExtracted.type === "single" &&
    currentExtracted.type === "single"
  ) {
    return (
      <MessagesDiff
        baseline={baselineExtracted.data}
        current={currentExtracted.data}
        variant={variant}
      />
    );
  }

  return (
    <NamedPromptsDiff
      baseline={baselineExtracted.data as NamedPrompts}
      current={currentExtracted.data as NamedPrompts}
      variant={variant}
    />
  );
};

export default PromptDiff;

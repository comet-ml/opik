import React, { useMemo } from "react";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import {
  OpenAIMessage,
  extractMessageContent,
  extractPromptData,
  NamedPrompts,
} from "@/lib/prompt";
import TextDiff from "./TextDiff";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";

type PromptDiffProps = {
  baseline: unknown;
  current: unknown;
};

const MessagesDiff: React.FC<{
  baseline: OpenAIMessage[];
  current: OpenAIMessage[];
}> = ({ baseline, current }) => {
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
    <div className="space-y-4">
      {allRoles.map((role) => {
        const baseContent = baselineByRole.get(role) || "";
        const currContent = currentByRole.get(role) || "";
        const hasChanged = baseContent !== currContent;
        const roleName =
          LLM_MESSAGE_ROLE_NAME_MAP[
            role as keyof typeof LLM_MESSAGE_ROLE_NAME_MAP
          ] || role;

        return (
          <div key={role} className="rounded-md border p-3">
            <div className="mb-2 flex items-center gap-2">
              <span className="comet-body-s-accented">{roleName}</span>
              {hasChanged && (
                <span className="comet-body-xs rounded-full bg-amber-100 px-2 py-0.5 text-amber-700">
                  Changed
                </span>
              )}
              {!baseContent && currContent && (
                <span className="comet-body-xs rounded-full bg-green-100 px-2 py-0.5 text-green-700">
                  Added
                </span>
              )}
              {baseContent && !currContent && (
                <span className="comet-body-xs rounded-full bg-red-100 px-2 py-0.5 text-red-700">
                  Removed
                </span>
              )}
            </div>
            <div className="comet-code whitespace-pre-wrap break-words text-sm">
              {hasChanged ? (
                <TextDiff content1={baseContent} content2={currContent} />
              ) : (
                <span className="text-muted-foreground">{currContent}</span>
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
}> = ({ baseline, current }) => {
  const allNames = useMemo(() => {
    const names = new Set([...Object.keys(baseline), ...Object.keys(current)]);
    return Array.from(names).sort();
  }, [baseline, current]);

  return (
    <Accordion type="multiple" defaultValue={allNames} className="w-full">
      {allNames.map((name) => {
        const baseMessages = baseline[name] || [];
        const currMessages = current[name] || [];

        return (
          <AccordionItem key={name} value={name}>
            <AccordionTrigger className="hover:no-underline">
              <span className="comet-body-s-accented">{name}</span>
            </AccordionTrigger>
            <AccordionContent>
              <MessagesDiff baseline={baseMessages} current={currMessages} />
            </AccordionContent>
          </AccordionItem>
        );
      })}
    </Accordion>
  );
};

const PromptDiff: React.FunctionComponent<PromptDiffProps> = ({
  baseline,
  current,
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

    return <TextDiff content1={baseText} content2={currText} />;
  }

  if (baselineExtracted.type !== currentExtracted.type) {
    const baseText = JSON.stringify(baseline, null, 2) ?? "";
    const currText = JSON.stringify(current, null, 2) ?? "";

    return <TextDiff content1={baseText} content2={currText} />;
  }

  if (
    baselineExtracted.type === "single" &&
    currentExtracted.type === "single"
  ) {
    return (
      <MessagesDiff
        baseline={baselineExtracted.data}
        current={currentExtracted.data}
      />
    );
  }

  return (
    <NamedPromptsDiff
      baseline={baselineExtracted.data as NamedPrompts}
      current={currentExtracted.data as NamedPrompts}
    />
  );
};

export default PromptDiff;

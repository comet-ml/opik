import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldPathValue, UseFormReturn } from "react-hook-form";
import cloneDeep from "lodash/cloneDeep";
import isEqual from "lodash/isEqual";

import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

export const RULE_HISTORY_SCOPE_ATTRIBUTE = "data-rule-history";
export const RULE_HISTORY_IGNORE_ATTRIBUTE = "data-rule-history-ignore";

const TRACKED_FIELDS = [
  "ruleName",
  "llmJudgeDetails.messages",
  "llmJudgeDetails.schema",
];
const TYPING_PAUSE_MS = 500;
const WORD_END_REGEX = /[\s.,;:!?]$/;

type EditorSnapshot = {
  llmJudgeDetails: FieldPathValue<EvaluationRuleFormType, "llmJudgeDetails">;
  ruleName: string;
  autoName: string;
  isPickingTemplate: boolean;
};

type TypingGroup = { at: number; isDeleting: boolean; endedWord: boolean };

const getContentText = ({ ruleName, llmJudgeDetails }: EditorSnapshot) =>
  [
    ruleName,
    ...llmJudgeDetails.messages.map(({ content }) =>
      typeof content === "string" ? content : JSON.stringify(content),
    ),
    ...llmJudgeDetails.schema.map(
      ({ name, description }) => `${name}\n${description}`,
    ),
  ].join("\n");

const getInsertedText = (previous: string, next: string) => {
  let start = 0;
  while (start < previous.length && previous[start] === next[start]) start++;
  let end = 0;
  while (
    end < previous.length - start &&
    previous[previous.length - 1 - end] === next[next.length - 1 - end]
  ) {
    end++;
  }
  return next.slice(start, next.length - end);
};

const isTrackedField = (name: string) =>
  TRACKED_FIELDS.some(
    (field) => name === field || name.startsWith(`${field}.`),
  );

const isSameContent = (a: EditorSnapshot, b: EditorSnapshot) =>
  a.ruleName === b.ruleName &&
  isEqual(a.llmJudgeDetails.messages, b.llmJudgeDetails.messages) &&
  isEqual(a.llmJudgeDetails.schema, b.llmJudgeDetails.schema);

const getHistoryCommand = (event: KeyboardEvent) => {
  if (!(event.metaKey || event.ctrlKey) || event.altKey) return null;
  const key = event.key.toLowerCase();
  if (key === "z") return event.shiftKey ? "redo" : "undo";
  if (key === "y" && !event.shiftKey) return "redo";
  return null;
};

const isOutsideHistoryScope = (element: Element | null) => {
  const isEditable =
    element instanceof HTMLInputElement ||
    element instanceof HTMLTextAreaElement ||
    (element instanceof HTMLElement && element.isContentEditable);
  return (
    isEditable &&
    (!element.closest(`[${RULE_HISTORY_SCOPE_ATTRIBUTE}]`) ||
      Boolean(element.closest(`[${RULE_HISTORY_IGNORE_ATTRIBUTE}]`)))
  );
};

const useRuleEditorHistory = (
  form: UseFormReturn<EvaluationRuleFormType>,
  autoNameRef: React.MutableRefObject<string>,
) => {
  const [isPickingTemplate, setIsPickingTemplateState] = useState(false);
  const isPickingTemplateRef = useRef(false);
  const pastRef = useRef<EditorSnapshot[]>([]);
  const futureRef = useRef<EditorSnapshot[]>([]);
  const currentRef = useRef<EditorSnapshot | null>(null);
  const isSilentRef = useRef(false);
  const typingGroupRef = useRef<TypingGroup | null>(null);

  const setIsPickingTemplate = useCallback((isPicking: boolean) => {
    isPickingTemplateRef.current = isPicking;
    setIsPickingTemplateState(isPicking);
  }, []);

  const takeSnapshot = useCallback(
    (): EditorSnapshot =>
      cloneDeep({
        llmJudgeDetails: form.getValues("llmJudgeDetails"),
        ruleName: form.getValues("ruleName"),
        autoName: autoNameRef.current,
        isPickingTemplate: isPickingTemplateRef.current,
      }),
    [form, autoNameRef],
  );

  const runSilently = useCallback((change: () => void) => {
    const wasSilent = isSilentRef.current;
    isSilentRef.current = true;
    try {
      change();
    } finally {
      isSilentRef.current = wasSilent;
    }
  }, []);

  const recordAction = useCallback(
    (action: () => void) => {
      const before = currentRef.current ?? takeSnapshot();
      runSilently(action);
      pastRef.current.push(before);
      futureRef.current = [];
      currentRef.current = takeSnapshot();
      typingGroupRef.current = null;
    },
    [takeSnapshot, runSilently],
  );

  const withoutHistory = useCallback(
    (change: () => void) => {
      runSilently(change);
      currentRef.current = takeSnapshot();
    },
    [takeSnapshot, runSilently],
  );

  const resetHistory = useCallback(
    (change: () => void) => {
      runSilently(change);
      pastRef.current = [];
      futureRef.current = [];
      currentRef.current = takeSnapshot();
      typingGroupRef.current = null;
    },
    [takeSnapshot, runSilently],
  );

  const clearHistory = useCallback(
    () => resetHistory(() => {}),
    [resetHistory],
  );

  const restore = useCallback(
    (snapshot: EditorSnapshot) => {
      const current = currentRef.current ?? takeSnapshot();
      if (!isSameContent(snapshot, current)) {
        const { model, config, maxCostUsd } = form.getValues("llmJudgeDetails");
        runSilently(() => {
          autoNameRef.current = snapshot.autoName;
          form.setValue("ruleName", snapshot.ruleName, { shouldDirty: true });
          form.setValue(
            "llmJudgeDetails",
            { ...snapshot.llmJudgeDetails, model, config, maxCostUsd },
            { shouldDirty: true },
          );
          form.clearErrors("llmJudgeDetails");
        });
      }
      setIsPickingTemplate(snapshot.isPickingTemplate);
      currentRef.current = takeSnapshot();
      typingGroupRef.current = null;
    },
    [form, autoNameRef, takeSnapshot, runSilently, setIsPickingTemplate],
  );

  const step = useCallback(
    (from: EditorSnapshot[], to: EditorSnapshot[]) => {
      const target = from.pop();
      if (!target) return;
      to.push(currentRef.current ?? takeSnapshot());
      restore(target);
    },
    [takeSnapshot, restore],
  );

  useEffect(() => {
    currentRef.current = takeSnapshot();
    const subscription = form.watch((_, { name }) => {
      if (isSilentRef.current || !name || !isTrackedField(name)) return;
      const previous = currentRef.current;
      const next = takeSnapshot();
      if (previous && isSameContent(previous, next)) return;
      const previousText = previous ? getContentText(previous) : "";
      const nextText = getContentText(next);
      const isDeleting = nextText.length < previousText.length;
      const now = Date.now();
      const group = typingGroupRef.current;
      const startsStep =
        !group ||
        now - group.at > TYPING_PAUSE_MS ||
        group.isDeleting !== isDeleting ||
        group.endedWord;
      if (previous && startsStep) {
        pastRef.current.push(previous);
        futureRef.current = [];
      }
      typingGroupRef.current = {
        at: now,
        isDeleting,
        endedWord:
          !isDeleting &&
          WORD_END_REGEX.test(getInsertedText(previousText, nextText)),
      };
      currentRef.current = next;
    });
    return () => subscription.unsubscribe();
  }, [form, takeSnapshot]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      const command = getHistoryCommand(event);
      if (
        !command ||
        form.getValues("uiType") === UI_EVALUATORS_RULE_TYPE.python_code ||
        isOutsideHistoryScope(document.activeElement)
      ) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      if (command === "undo") step(pastRef.current, futureRef.current);
      else step(futureRef.current, pastRef.current);
    };
    window.addEventListener("keydown", handleKeyDown, true);
    return () => window.removeEventListener("keydown", handleKeyDown, true);
  }, [form, step]);

  return {
    isPickingTemplate,
    setIsPickingTemplate,
    recordAction,
    resetHistory,
    clearHistory,
    withoutHistory,
  };
};

export default useRuleEditorHistory;

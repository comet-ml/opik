import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldPathValue, UseFormReturn } from "react-hook-form";
import cloneDeep from "lodash/cloneDeep";
import isEqual from "lodash/isEqual";

import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";

export const RULE_HISTORY_SCOPE_ATTRIBUTE = "data-rule-history";

const TRACKED_FIELDS = [
  "ruleName",
  "llmJudgeDetails.messages",
  "llmJudgeDetails.schema",
];
const TYPING_GROUP_MS = 1000;

type EditorSnapshot = {
  llmJudgeDetails: FieldPathValue<EvaluationRuleFormType, "llmJudgeDetails">;
  ruleName: string;
  autoName: string;
  isPickingTemplate: boolean;
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
  return isEditable && !element.closest(`[${RULE_HISTORY_SCOPE_ATTRIBUTE}]`);
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
  const lastTypingAtRef = useRef(0);

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
    isSilentRef.current = true;
    try {
      change();
    } finally {
      isSilentRef.current = false;
    }
  }, []);

  const recordAction = useCallback(
    (action: () => void) => {
      const before = currentRef.current ?? takeSnapshot();
      runSilently(action);
      pastRef.current.push(before);
      futureRef.current = [];
      currentRef.current = takeSnapshot();
      lastTypingAtRef.current = 0;
    },
    [takeSnapshot, runSilently],
  );

  const resetHistory = useCallback(
    (change: () => void) => {
      runSilently(change);
      pastRef.current = [];
      futureRef.current = [];
      currentRef.current = takeSnapshot();
      lastTypingAtRef.current = 0;
    },
    [takeSnapshot, runSilently],
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
      lastTypingAtRef.current = 0;
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
      const now = Date.now();
      if (
        currentRef.current &&
        now - lastTypingAtRef.current > TYPING_GROUP_MS
      ) {
        pastRef.current.push(currentRef.current);
        futureRef.current = [];
      }
      lastTypingAtRef.current = now;
      currentRef.current = takeSnapshot();
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
  };
};

export default useRuleEditorHistory;

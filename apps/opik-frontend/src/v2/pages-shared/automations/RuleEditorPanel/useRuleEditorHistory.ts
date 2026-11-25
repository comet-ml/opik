import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldPath, UseFormReturn } from "react-hook-form";
import cloneDeep from "lodash/cloneDeep";
import isBoolean from "lodash/isBoolean";
import isEqual from "lodash/isEqual";
import isNumber from "lodash/isNumber";
import isObjectLike from "lodash/isObjectLike";
import omit from "lodash/omit";

import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import {
  DERIVED_FIELDS,
  isDerivedField,
} from "@/v2/pages-shared/automations/RuleEditorPanel/constants";

export const RULE_HISTORY_SCOPE_ATTRIBUTE = "data-rule-history";
export const RULE_HISTORY_IGNORE_ATTRIBUTE = "data-rule-history-ignore";

const TYPING_PAUSE_MS = 500;
const WORD_END_REGEX = /[\s.,;:!?]$/;

type FormField = FieldPath<EvaluationRuleFormType>;

type EditorSnapshot = {
  values: EvaluationRuleFormType;
  autoName: string;
  isPickingTemplate: boolean;
};

type TypingGroup = {
  field: string;
  isText: boolean;
  at: number;
  isDeleting: boolean;
  endedWord: boolean;
};

const getTrackedValues = ({ values }: EditorSnapshot) =>
  omit(values, DERIVED_FIELDS);

const collectText = (value: unknown): string[] =>
  isObjectLike(value)
    ? Object.values(value as object).flatMap(collectText)
    : [String(value ?? "")];

const getContentText = (snapshot: EditorSnapshot) =>
  collectText(getTrackedValues(snapshot)).join("\n");

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

const isSameContent = (a: EditorSnapshot, b: EditorSnapshot) =>
  isEqual(getTrackedValues(a), getTrackedValues(b));

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
  const isSameTaskRef = useRef(false);

  const setIsPickingTemplate = useCallback((isPicking: boolean) => {
    isPickingTemplateRef.current = isPicking;
    setIsPickingTemplateState(isPicking);
  }, []);

  const takeSnapshot = useCallback(
    (): EditorSnapshot =>
      cloneDeep({
        values: form.getValues(),
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

  const clearHistory = useCallback(() => {
    pastRef.current = [];
    futureRef.current = [];
    currentRef.current = takeSnapshot();
    typingGroupRef.current = null;
  }, [takeSnapshot]);

  const restore = useCallback(
    (snapshot: EditorSnapshot) => {
      const current = form.getValues();
      const changedFields = (
        Object.keys(snapshot.values) as (keyof EvaluationRuleFormType)[]
      ).filter((key) => !isEqual(current[key], snapshot.values[key]));
      runSilently(() => {
        autoNameRef.current = snapshot.autoName;
        changedFields.forEach((key) =>
          form.setValue(
            key as FormField,
            cloneDeep(snapshot.values[key]) as never,
            { shouldDirty: true },
          ),
        );
        if (changedFields.length > 0) {
          form.clearErrors(changedFields as FormField[]);
        }
      });
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
      if (isSilentRef.current || !name || isDerivedField(name)) return;
      const previous = currentRef.current;
      const next = takeSnapshot();
      if (previous && isSameContent(previous, next)) return;

      const isSameTask = isSameTaskRef.current;
      isSameTaskRef.current = true;
      queueMicrotask(() => {
        isSameTaskRef.current = false;
      });

      const value = form.getValues(name);
      const isText = !isNumber(value) && !isBoolean(value);
      const previousText = previous ? getContentText(previous) : "";
      const nextText = getContentText(next);
      const isDeleting = nextText.length < previousText.length;
      const now = Date.now();
      const group = typingGroupRef.current;
      const startsStep =
        !isSameTask &&
        (!group ||
          (group.field !== name && !(isText && group.isText)) ||
          isBoolean(value) ||
          now - group.at > TYPING_PAUSE_MS ||
          (isText && (group.isDeleting !== isDeleting || group.endedWord)));
      if (previous && startsStep) {
        pastRef.current.push(previous);
        futureRef.current = [];
      }
      typingGroupRef.current = {
        ...(isSameTask && group
          ? { field: group.field, isText: group.isText }
          : { field: name, isText }),
        at: now,
        isDeleting,
        endedWord:
          isText &&
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
    clearHistory,
    withoutHistory,
  };
};

export default useRuleEditorHistory;

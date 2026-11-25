import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldPath, UseFormReturn } from "react-hook-form";
import cloneDeep from "lodash/cloneDeep";
import isEqual from "lodash/isEqual";
import omit from "lodash/omit";

import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import {
  DERIVED_FIELDS,
  isDerivedField,
} from "@/v2/pages-shared/automations/RuleEditorPanel/constants";
import {
  commit,
  createHistory,
  redo,
  replacePresent,
  undo,
  UndoHistory,
} from "@/v2/pages-shared/automations/RuleEditorPanel/undoHistory";
import {
  describeEdit,
  Edit,
  startsNewStep,
  toText,
} from "@/v2/pages-shared/automations/RuleEditorPanel/undoStepGrouping";
import useUndoRedoShortcuts from "@/v2/pages-shared/automations/RuleEditorPanel/useUndoRedoShortcuts";

type FormField = FieldPath<EvaluationRuleFormType>;
type FormKey = keyof EvaluationRuleFormType;

type EditorSnapshot = {
  values: EvaluationRuleFormType;
  autoName: string;
  isPickingTemplate: boolean;
};

const getTrackedValues = ({ values }: EditorSnapshot) =>
  omit(values, DERIVED_FIELDS);

const isSameContent = (a: EditorSnapshot, b: EditorSnapshot) =>
  isEqual(getTrackedValues(a), getTrackedValues(b));

const getContentText = (snapshot: EditorSnapshot) =>
  toText(getTrackedValues(snapshot));

const getChangedKeys = (
  current: EvaluationRuleFormType,
  target: EvaluationRuleFormType,
) =>
  (Object.keys(target) as FormKey[]).filter(
    (key) => !isEqual(current[key], target[key]),
  );

const createSameTaskCheck = () => {
  let isInTask = false;
  return () => {
    if (isInTask) return true;
    isInTask = true;
    queueMicrotask(() => {
      isInTask = false;
    });
    return false;
  };
};

const useStateWithRef = <T>(initialValue: T) => {
  const [value, setValue] = useState(initialValue);
  const ref = useRef(initialValue);
  const set = useCallback((next: T) => {
    ref.current = next;
    setValue(next);
  }, []);
  return [value, set, ref] as const;
};

const useRuleEditorHistory = (
  form: UseFormReturn<EvaluationRuleFormType>,
  autoNameRef: React.MutableRefObject<string>,
) => {
  const [isPickingTemplate, setIsPickingTemplate, isPickingTemplateRef] =
    useStateWithRef(false);
  const historyRef = useRef<UndoHistory<EditorSnapshot> | null>(null);
  const lastEditRef = useRef<Edit | null>(null);
  const isPausedRef = useRef(false);
  const isSameTaskRef = useRef(createSameTaskCheck());

  const takeSnapshot = useCallback(
    (): EditorSnapshot =>
      cloneDeep({
        values: form.getValues(),
        autoName: autoNameRef.current,
        isPickingTemplate: isPickingTemplateRef.current,
      }),
    [form, autoNameRef, isPickingTemplateRef],
  );

  const getHistory = useCallback(
    () => (historyRef.current ??= createHistory(takeSnapshot())),
    [takeSnapshot],
  );

  const setHistory = useCallback(
    (history: UndoHistory<EditorSnapshot>, lastEdit: Edit | null = null) => {
      historyRef.current = history;
      lastEditRef.current = lastEdit;
    },
    [],
  );

  const withHistoryPaused = useCallback((change: () => void) => {
    const wasPaused = isPausedRef.current;
    isPausedRef.current = true;
    try {
      change();
    } finally {
      isPausedRef.current = wasPaused;
    }
  }, []);

  const recordAction = useCallback(
    (action: () => void) => {
      const history = getHistory();
      withHistoryPaused(action);
      setHistory(commit(history, takeSnapshot()));
    },
    [getHistory, withHistoryPaused, setHistory, takeSnapshot],
  );

  const withoutHistory = useCallback(
    (change: () => void) => {
      withHistoryPaused(change);
      historyRef.current = replacePresent(getHistory(), takeSnapshot());
    },
    [getHistory, withHistoryPaused, takeSnapshot],
  );

  const clearHistory = useCallback(
    () => setHistory(createHistory(takeSnapshot())),
    [setHistory, takeSnapshot],
  );

  const applySnapshot = useCallback(
    ({ values, autoName, isPickingTemplate }: EditorSnapshot) => {
      const changedKeys = getChangedKeys(form.getValues(), values);
      withHistoryPaused(() => {
        autoNameRef.current = autoName;
        changedKeys.forEach((key) =>
          form.setValue(key as FormField, cloneDeep(values[key]) as never, {
            shouldDirty: true,
          }),
        );
        form.clearErrors(changedKeys as FormField[]);
      });
      setIsPickingTemplate(isPickingTemplate);
    },
    [form, autoNameRef, withHistoryPaused, setIsPickingTemplate],
  );

  const moveInHistory = useCallback(
    (move: typeof undo) => {
      const history = getHistory();
      const moved = move(history);
      if (moved === history) return;
      applySnapshot(moved.present);
      setHistory(replacePresent(moved, takeSnapshot()));
    },
    [getHistory, applySnapshot, setHistory, takeSnapshot],
  );

  const recordFieldChange = useCallback(
    (name: FormField) => {
      const history = getHistory();
      const next = takeSnapshot();
      if (isSameContent(history.present, next)) return;
      if (isSameTaskRef.current()) {
        historyRef.current = replacePresent(history, next);
        return;
      }
      const edit = describeEdit(
        form.getValues(name),
        getContentText(history.present),
        getContentText(next),
      );
      setHistory(
        startsNewStep(lastEditRef.current, edit)
          ? commit(history, next)
          : replacePresent(history, next),
        edit,
      );
    },
    [form, getHistory, setHistory, takeSnapshot],
  );

  useEffect(() => {
    historyRef.current = createHistory(takeSnapshot());
    const subscription = form.watch((_, { name }) => {
      if (isPausedRef.current || !name || isDerivedField(name)) return;
      recordFieldChange(name);
    });
    return () => subscription.unsubscribe();
  }, [form, takeSnapshot, recordFieldChange]);

  const undoStep = useCallback(() => moveInHistory(undo), [moveInHistory]);
  const redoStep = useCallback(() => moveInHistory(redo), [moveInHistory]);

  useUndoRedoShortcuts({
    isEnabled: form.getValues("uiType") !== UI_EVALUATORS_RULE_TYPE.python_code,
    onUndo: undoStep,
    onRedo: redoStep,
  });

  return {
    isPickingTemplate,
    setIsPickingTemplate,
    recordAction,
    clearHistory,
    withoutHistory,
  };
};

export default useRuleEditorHistory;

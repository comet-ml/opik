import { useReducer } from "react";
import {
  GuardrailFields,
  GuardrailsState,
  guardrailsDefaultState,
} from "./guardrailsConfig";
import { GuardrailTypes } from "@/types/guardrails";

enum ActionType {
  UPDATE_FIELD = "UPDATE_FIELD",
  TOGGLE_ENABLED = "TOGGLE_ENABLED",
}

type GuardrailAction =
  | {
      type: ActionType.UPDATE_FIELD;
      payload: {
        guardrailType: GuardrailTypes;
        field: keyof GuardrailFields;
        value: GuardrailFields[keyof GuardrailFields];
      };
    }
  | {
      type: ActionType.TOGGLE_ENABLED;
      payload: { guardrailType: GuardrailTypes };
    };

const guardrailReducer = (
  state: GuardrailsState,
  action: GuardrailAction,
): GuardrailsState => {
  const { type, payload } = action;

  switch (type) {
    case ActionType.UPDATE_FIELD:
      return {
        ...state,
        [payload.guardrailType]: {
          ...state[payload.guardrailType],
          [payload.field]: payload.value,
        },
      };
    case ActionType.TOGGLE_ENABLED:
      return {
        ...state,
        [payload.guardrailType]: {
          ...state[payload.guardrailType],
          enabled: !state[payload.guardrailType].enabled,
        },
      };
    default:
      return state;
  }
};

export const useGuardrailConfigState = (
  initialState: GuardrailsState = guardrailsDefaultState,
) => {
  const [state, dispatch] = useReducer(guardrailReducer, initialState);

  const updateField = <K extends keyof GuardrailFields>(
    guardrailType: GuardrailTypes,
    field: K,
    value: GuardrailFields[K],
  ) => {
    dispatch({
      type: ActionType.UPDATE_FIELD,
      payload: { guardrailType, field, value },
    });
  };

  const toggleEnabled = (guardrailType: GuardrailTypes) => {
    dispatch({
      type: ActionType.TOGGLE_ENABLED,
      payload: { guardrailType },
    });
  };

  const getEnabledGuardrailTypes = (): GuardrailTypes[] => {
    return Object.keys(state).filter(
      (key) => state[key as GuardrailTypes].enabled,
    ) as GuardrailTypes[];
  };

  return {
    state,
    updateField,
    toggleEnabled,
    getEnabledGuardrailTypes,
  };
};

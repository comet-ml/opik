import { useReducer } from "react";
import { GuardrailsState, guardrailsDefaultState } from "./guardrailsConfig";
import { QuickConfigGuardrailType } from "@/types/guardrails";

enum ActionType {
  UPDATE_THRESHOLD = "UPDATE_THRESHOLD",
  UPDATE_ENTITIES = "UPDATE_ENTITIES",
  TOGGLE_ENABLED = "TOGGLE_ENABLED",
}

type GuardrailAction =
  | {
      type: ActionType.UPDATE_THRESHOLD;
      payload: { guardrailType: QuickConfigGuardrailType; threshold: number };
    }
  | {
      type: ActionType.UPDATE_ENTITIES;
      payload: { guardrailType: QuickConfigGuardrailType; entities: string[] };
    }
  | {
      type: ActionType.TOGGLE_ENABLED;
      payload: { guardrailType: QuickConfigGuardrailType };
    };

const guardrailReducer = (
  state: GuardrailsState,
  action: GuardrailAction,
): GuardrailsState => {
  const { type, payload } = action;

  switch (type) {
    case ActionType.UPDATE_THRESHOLD:
      return {
        ...state,
        [payload.guardrailType]: {
          ...state[payload.guardrailType],
          threshold: payload.threshold,
        },
      };
    case ActionType.UPDATE_ENTITIES:
      return {
        ...state,
        [payload.guardrailType]: {
          ...state[payload.guardrailType],
          entities: payload.entities,
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

  const updateThreshold = (
    guardrailType: QuickConfigGuardrailType,
    threshold: number,
  ) => {
    dispatch({
      type: ActionType.UPDATE_THRESHOLD,
      payload: { guardrailType, threshold },
    });
  };

  const updateEntities = (
    guardrailType: QuickConfigGuardrailType,
    entities: string[],
  ) => {
    dispatch({
      type: ActionType.UPDATE_ENTITIES,
      payload: { guardrailType, entities },
    });
  };

  const toggleEnabled = (guardrailType: QuickConfigGuardrailType) => {
    dispatch({
      type: ActionType.TOGGLE_ENABLED,
      payload: { guardrailType },
    });
  };

  const getEnabledGuardrailTypes = (): QuickConfigGuardrailType[] => {
    return Object.keys(state).filter(
      (key) => state[key as QuickConfigGuardrailType].enabled,
    ) as QuickConfigGuardrailType[];
  };

  return {
    state,
    updateThreshold,
    updateEntities,
    toggleEnabled,
    getEnabledGuardrailTypes,
  };
};

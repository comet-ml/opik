import { useReducer } from "react";
import { GuardrailsState, guardrailsDefaultState } from "./guardrailsConfig";
import { GuardrailTypes } from "@/types/guardrails";

enum ActionType {
  UPDATE_THRESHOLD = "UPDATE_THRESHOLD",
  UPDATE_ENTITIES = "UPDATE_ENTITIES",
  TOGGLE_ENABLED = "TOGGLE_ENABLED",
}

type GuardrailAction =
  | {
      type: ActionType.UPDATE_THRESHOLD;
      payload: { guardrailType: GuardrailTypes; threshold: number };
    }
  | {
      type: ActionType.UPDATE_ENTITIES;
      payload: { guardrailType: GuardrailTypes; entities: string[] };
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
    guardrailType: GuardrailTypes,
    threshold: number,
  ) => {
    dispatch({
      type: ActionType.UPDATE_THRESHOLD,
      payload: { guardrailType, threshold },
    });
  };

  const updateEntities = (
    guardrailType: GuardrailTypes,
    entities: string[],
  ) => {
    dispatch({
      type: ActionType.UPDATE_ENTITIES,
      payload: { guardrailType, entities },
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
    updateThreshold,
    updateEntities,
    toggleEnabled,
    getEnabledGuardrailTypes,
  };
};

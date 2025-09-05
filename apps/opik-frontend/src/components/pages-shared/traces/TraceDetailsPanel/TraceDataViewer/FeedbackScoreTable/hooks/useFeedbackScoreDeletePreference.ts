import useLocalStorageState from "node_modules/use-local-storage-state/src/useLocalStorageState";

export const useFeedbackScoreDeletePreference = () => {
  return useLocalStorageState<boolean>("feedback-score-delete-dont-ask-again", {
    defaultValue: false,
  });
};

import useLocalStorageState from "use-local-storage-state";

export const useFeedbackScoreDeletePreference = () => {
  return useLocalStorageState<boolean>("feedback-score-delete-dont-ask-again", {
    defaultValue: false,
  });
};

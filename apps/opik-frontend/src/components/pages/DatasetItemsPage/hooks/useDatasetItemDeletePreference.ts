import useLocalStorageState from "use-local-storage-state";

export const useDatasetItemDeletePreference = () => {
  return useLocalStorageState<boolean>("dataset-item-delete-dont-ask-again", {
    defaultValue: false,
  });
};

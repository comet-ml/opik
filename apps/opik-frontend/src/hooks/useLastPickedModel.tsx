import useLocalStorageState from "use-local-storage-state";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

const useLastPickedModel = ({ key = "last-picked-model" }: { key: string }) => {
  return useLocalStorageState<PROVIDER_MODEL_TYPE | "">(key, {
    defaultValue: "",
  });
};

export default useLastPickedModel;

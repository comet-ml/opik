import useLocalStorageState from "use-local-storage-state";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

const PLAYGROUND_LAST_PICKED_MODEL = "playground-last-picked-model";

const useLastPickedModel = () => {
  return useLocalStorageState<PROVIDER_MODEL_TYPE | "">(
    PLAYGROUND_LAST_PICKED_MODEL,
    {
      defaultValue: "",
    },
  );
};

export default useLastPickedModel;

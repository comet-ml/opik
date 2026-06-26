import { AxiosError } from "axios";
import get from "lodash/get";
import { useToast } from "@/ui/use-toast";

type ToastFn = ReturnType<typeof useToast>["toast"];

export const handleMutationError = (toast: ToastFn, error: AxiosError) => {
  const errors = get(error, ["response", "data", "errors"], []);
  const message =
    Array.isArray(errors) && errors.length > 0
      ? errors.join("; ")
      : get(error, ["response", "data", "message"], error.message);
  toast({ title: "Error", description: message, variant: "destructive" });
};

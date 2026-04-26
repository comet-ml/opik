import { useEffect, useRef } from "react";
import axios, { AxiosError } from "axios";

import { useToast } from "@/ui/use-toast";
import { extractErrorMessage } from "@/lib/tags";

interface UseQueryErrorToastParams {
  isError: boolean;
  error: Error | null;
}

const useQueryErrorToast = ({ isError, error }: UseQueryErrorToastParams) => {
  const { toast } = useToast();
  const shownRef = useRef(false);

  useEffect(() => {
    if (isError && error && !shownRef.current) {
      if (axios.isCancel(error)) return;

      const status = (error as AxiosError)?.response?.status ?? 0;
      if (status >= 500 || status === 0) {
        shownRef.current = true;
        const message = extractErrorMessage(error as AxiosError);

        toast({
          title: "Error",
          description: message,
          variant: "destructive",
        });
      }
    }

    if (!isError) {
      shownRef.current = false;
    }
  }, [isError, error, toast]);
};

export default useQueryErrorToast;

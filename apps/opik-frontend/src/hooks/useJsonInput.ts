import { useCallback, useEffect, useState } from "react";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";

interface UseJsonInputProps {
  value: Record<string, unknown> | null | undefined;
  onChange: (value: Record<string, unknown> | null) => void;
}

interface UseJsonInputReturn {
  jsonString: string;
  showInvalidJSON: boolean;
  handleJsonChange: (value: string) => void;
  handleJsonBlur: () => void;
}

const useJsonInput = ({
  value,
  onChange,
}: UseJsonInputProps): UseJsonInputReturn => {
  const [jsonString, setJsonString] = useState<string>("");
  const [showInvalidJSON, setShowInvalidJSON] = useState<boolean>(false);

  useEffect(() => {
    if (value) {
      setJsonString(JSON.stringify(value, null, 2));
    } else {
      setJsonString("");
    }
  }, [value]);

  const handleJsonChange = useCallback((newValue: string) => {
    setJsonString(newValue);
    setShowInvalidJSON(false);
  }, []);

  const handleJsonBlur = useCallback(() => {
    const trimmedValue = jsonString.trim();

    if (trimmedValue === "") {
      onChange(null);
      setShowInvalidJSON(false);
      return;
    }

    const isValid = isValidJsonObject(trimmedValue);
    if (isValid) {
      const parsed = safelyParseJSON(trimmedValue);
      onChange(parsed);
      setShowInvalidJSON(false);
    } else {
      setShowInvalidJSON(true);
    }
  }, [jsonString, onChange]);

  return {
    jsonString,
    showInvalidJSON,
    handleJsonChange,
    handleJsonBlur,
  };
};

export default useJsonInput;

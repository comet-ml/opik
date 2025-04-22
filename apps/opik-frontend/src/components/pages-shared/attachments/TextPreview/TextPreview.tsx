import React from "react";
import { useQuery } from "@tanstack/react-query";
import isString from "lodash/isString";

import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

interface TextPreviewProps {
  url: string;
}

const TextPreview: React.FC<TextPreviewProps> = ({ url }) => {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ["text", url],
    queryFn: async () => {
      try {
        const response = await fetch(url);
        return await response.text();
      } catch (error) {
        let message: string | undefined;
        if (isString(error)) {
          message = error;
        } else if (error instanceof Error) {
          message = error.message;
        }
        throw new Error(
          message ?? "Failed to fetch text. CORS issue or invalid URL.",
        );
      }
    },
  });

  const renderContent = () => {
    if (isPending) return <Loader />;

    if (isError) return <NoData icon={null} message={error?.message} />;

    return isString(data) ? (
      <div className="min-h-full min-w-[800px]">
        <MarkdownPreview>{data}</MarkdownPreview>
      </div>
    ) : (
      <NoData message="Data is not a text" icon={null} />
    );
  };

  return (
    <div className="relative flex size-full justify-center overflow-y-auto pb-10">
      {renderContent()}
    </div>
  );
};

export default TextPreview;

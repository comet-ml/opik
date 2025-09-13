import React from "react";
import { useParams, useSearch } from "@tanstack/react-router";

import Loader from "@/components/shared/Loader/Loader";
import SMEInstructionsPage from "./SMEInstructionsPage";
import SMEAnnotationPage from "./SMEAnnotationPage";
import useSMEQueue from "@/api/annotation-queues/useSMEQueue";
import useSMEProgress from "@/api/annotation-queues/useSMEProgress";

const SMEQueuePage: React.FunctionComponent = () => {
  const params = useParams({ strict: false });
  const search = useSearch({ strict: false });
  const shareToken = params.shareToken as string;
  const view = (search as any)?.view as "instructions" | "annotate" | undefined;
  
  const currentView = view || "instructions";


  const { data: queue, isPending: isQueueLoading, error: queueError } = useSMEQueue({
    shareToken,
  });

  const { data: progress, isPending: isProgressLoading } = useSMEProgress({
    shareToken,
  }, {
    enabled: !!queue,
  });

  if (isQueueLoading || isProgressLoading) {
    return (
      <div className="min-h-screen bg-white flex items-center justify-center">
        <div className="text-center">
          <Loader />
          <p className="text-gray-500 mt-4">Loading annotation queue...</p>
        </div>
      </div>
    );
  }

  if (queueError || !queue) {
    return (
      <div className="min-h-screen bg-white flex items-center justify-center">
        <div className="text-center max-w-md">
          <div className="mb-4">
            <div className="inline-flex items-center justify-center w-16 h-16 bg-red-100 rounded-full mb-4">
              <span className="text-red-600 text-2xl">!</span>
            </div>
          </div>
          <h2 className="text-2xl font-bold text-gray-900 mb-2">
            Queue Not Found
          </h2>
          <p className="text-gray-600">
            The annotation queue you're looking for doesn't exist or is no longer available.
          </p>
        </div>
      </div>
    );
  }

  // Show instructions by default, annotation view when explicitly requested
  if (currentView === "annotate") {
    return <SMEAnnotationPage queue={queue} shareToken={shareToken} />;
  }

  return <SMEInstructionsPage queue={queue} shareToken={shareToken} />;
};

export default SMEQueuePage;

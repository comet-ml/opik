const AI_SPEND_ROUTE_SEGMENT = "/ai-spend";

export const isAiSpendRoute = (pathname: string): boolean => {
  const normalized = pathname.replace(/\/+$/, "");
  return (
    normalized.endsWith(AI_SPEND_ROUTE_SEGMENT) ||
    normalized.includes(`${AI_SPEND_ROUTE_SEGMENT}/`)
  );
};

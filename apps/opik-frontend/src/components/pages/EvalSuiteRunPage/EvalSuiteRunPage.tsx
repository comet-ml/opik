import React, { useMemo } from "react";
import { Link, useParams } from "@tanstack/react-router";
import { ArrowLeft, Check, X, ExternalLink } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import useEvalSuiteRun from "@/api/eval-runs/useEvalSuiteRun";
import { cn } from "@/lib/utils";

const EvalSuiteRunPage: React.FC = () => {
  const { projectId, runId } = useParams({ strict: false }) as {
    projectId: string;
    runId: string;
  };
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data, isPending, isError } = useEvalSuiteRun({ runId });

  const stats = useMemo(() => {
    if (!data?.items) return { passed: 0, failed: 0, total: 0 };
    const passed = data.items.filter((item) => !item.reason).length;
    const failed = data.items.length - passed;
    return { passed, failed, total: data.items.length };
  }, [data?.items]);

  if (isPending) {
    return <Loader />;
  }

  if (isError || !data) {
    return (
      <PageBodyScrollContainer>
        <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
          <Link
            to="/$workspaceName/projects/$projectId/traces"
            params={{ workspaceName, projectId }}
          >
            <Button variant="ghost" size="sm">
              <ArrowLeft className="mr-1.5 size-4" />
              Back to Traces
            </Button>
          </Link>
          <div className="mt-8 text-center text-muted-slate">
            Evaluation run not found
          </div>
        </PageBodyStickyContainer>
      </PageBodyScrollContainer>
    );
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth className="pt-6">
        <Link
          to="/$workspaceName/projects/$projectId/traces"
          params={{ workspaceName, projectId }}
        >
          <Button variant="ghost" size="sm" className="mb-4">
            <ArrowLeft className="mr-1.5 size-4" />
            Back to Traces
          </Button>
        </Link>

        <div className="mb-6">
          <h1 className="comet-title-l mb-2">Regression Test Results</h1>
          <div className="flex items-center gap-4 text-sm">
            <span className="text-muted-slate">
              Run ID: <code className="font-mono">{runId}</code>
            </span>
            {data.suite_name && (
              <span className="text-muted-slate">
                Suite: <span className="font-medium text-foreground">{data.suite_name}</span>
              </span>
            )}
          </div>
        </div>

        <div className="mb-6 flex gap-4">
          <div className="rounded-lg border bg-muted/30 px-4 py-3">
            <div className="text-2xl font-semibold text-foreground">{stats.total}</div>
            <div className="text-sm text-muted-slate">Total Items</div>
          </div>
          <div className="rounded-lg border bg-green-50 px-4 py-3">
            <div className="text-2xl font-semibold text-green-600">{stats.passed}</div>
            <div className="text-sm text-green-700">Passed</div>
          </div>
          <div className="rounded-lg border bg-red-50 px-4 py-3">
            <div className="text-2xl font-semibold text-red-600">{stats.failed}</div>
            <div className="text-sm text-red-700">Failed</div>
          </div>
        </div>

        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-12">Status</TableHead>
                <TableHead>Item ID</TableHead>
                <TableHead>Reason</TableHead>
                <TableHead className="w-24">Trace</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.items?.map((item) => {
                const passed = !item.reason;
                return (
                  <TableRow key={item.item_id}>
                    <TableCell>
                      {passed ? (
                        <Check className="size-4 text-green-600" />
                      ) : (
                        <X className="size-4 text-red-500" />
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-sm">
                      {item.item_id}
                    </TableCell>
                    <TableCell className={cn(!passed && "text-red-600")}>
                      {item.reason || "-"}
                    </TableCell>
                    <TableCell>
                      {item.trace_id ? (
                        <Link
                          to="/$workspaceName/projects/$projectId/traces"
                          params={{ workspaceName, projectId }}
                          search={{ trace: item.trace_id }}
                          className="flex items-center gap-1 text-sm text-primary hover:underline"
                        >
                          View
                          <ExternalLink className="size-3" />
                        </Link>
                      ) : (
                        <span className="text-muted-slate">-</span>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
              {(!data.items || data.items.length === 0) && (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-muted-slate">
                    No items in this run
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </PageBodyStickyContainer>
    </PageBodyScrollContainer>
  );
};

export default EvalSuiteRunPage;

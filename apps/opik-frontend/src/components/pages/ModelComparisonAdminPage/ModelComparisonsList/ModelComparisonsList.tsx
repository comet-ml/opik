import React, { useState, useMemo } from "react";
import { MoreHorizontal, Play, Trash2, Edit, Download } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import Loader from "@/components/shared/Loader/Loader";
import useModelComparisonsList from "@/api/model-comparisons/useModelComparisonsList";
import { useDeleteModelComparison } from "@/api/model-comparisons/useDeleteModelComparison";
import { useRunModelComparisonAnalysis } from "@/api/model-comparisons/useRunModelComparisonAnalysis";
import { ModelComparison } from "@/api/model-comparisons/useModelComparisonsList";

interface ModelComparisonsListProps {
  onComparisonSelect: (comparison: ModelComparison) => void;
}

const ModelComparisonsList: React.FunctionComponent<ModelComparisonsListProps> = ({
  onComparisonSelect,
}) => {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [sorting, setSorting] = useState("created_at_desc");

  const { data, isLoading, error } = useModelComparisonsList({
    page,
    size: 10,
    search: search || undefined,
    sorting,
  });

  const deleteMutation = useDeleteModelComparison();
  const runAnalysisMutation = useRunModelComparisonAnalysis();

  const handleDelete = (id: string) => {
    if (confirm("Are you sure you want to delete this comparison?")) {
      deleteMutation.mutate(id);
    }
  };

  const handleRunAnalysis = (id: string) => {
    runAnalysisMutation.mutate(id);
  };

  const filteredComparisons = useMemo(() => {
    if (!data?.content) return [];
    return data.content;
  }, [data?.content]);

  if (isLoading) {
    return <Loader />;
  }

  if (error) {
    return (
      <Card>
        <CardContent className="flex items-center justify-center py-12">
          <div className="text-center">
            <h3 className="comet-title-m mb-2">Error Loading Comparisons</h3>
            <p className="comet-body text-muted-foreground">
              {error.message || "Failed to load model comparisons"}
            </p>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      {/* Search and Filters */}
      <div className="flex items-center gap-4">
        <div className="flex-1">
          <Input
            placeholder="Search comparisons..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="max-w-sm"
          />
        </div>
        <div className="flex items-center gap-2">
          <span className="comet-body-s text-muted-foreground">
            {data?.total || 0} comparisons
          </span>
        </div>
      </div>

      {/* Comparisons Table */}
      <Card>
        <CardHeader>
          <CardTitle>Model Comparisons</CardTitle>
          <CardDescription>
            Compare LLM models across accuracy, cost, and performance metrics
          </CardDescription>
        </CardHeader>
        <CardContent>
          {filteredComparisons.length === 0 ? (
            <DataTableNoData 
              title="No model comparisons found"
              description="Create your first model comparison to get started"
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Models</TableHead>
                  <TableHead>Datasets</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead className="w-[50px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredComparisons.map((comparison) => (
                  <TableRow 
                    key={comparison.id}
                    className="cursor-pointer hover:bg-muted/50"
                    onClick={() => onComparisonSelect(comparison)}
                  >
                    <TableCell>
                      <div>
                        <div className="comet-title-s">{comparison.name}</div>
                        {comparison.description && (
                          <div className="comet-body-xs text-muted-foreground mt-1">
                            {comparison.description}
                          </div>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {comparison.model_ids.slice(0, 2).map((modelId) => (
                          <Badge key={modelId} variant="outline" className="text-xs">
                            {modelId}
                          </Badge>
                        ))}
                        {comparison.model_ids.length > 2 && (
                          <Badge variant="outline" className="text-xs">
                            +{comparison.model_ids.length - 2} more
                          </Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {comparison.dataset_names.slice(0, 2).map((datasetName) => (
                          <Badge key={datasetName} variant="secondary" className="text-xs">
                            {datasetName}
                          </Badge>
                        ))}
                        {comparison.dataset_names.length > 2 && (
                          <Badge variant="secondary" className="text-xs">
                            +{comparison.dataset_names.length - 2} more
                          </Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge 
                        variant={comparison.results ? "default" : "secondary"}
                      >
                        {comparison.results ? "Analyzed" : "Pending"}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="comet-body-s">
                        {new Date(comparison.created_at).toLocaleDateString()}
                      </div>
                      <div className="comet-body-xs text-muted-foreground">
                        by {comparison.created_by || "Unknown"}
                      </div>
                    </TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button 
                            variant="ghost" 
                            size="icon"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <MoreHorizontal className="h-4 w-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation();
                              onComparisonSelect(comparison);
                            }}
                          >
                            <Edit className="h-4 w-4 mr-2" />
                            View Details
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation();
                              handleRunAnalysis(comparison.id);
                            }}
                            disabled={runAnalysisMutation.isPending}
                          >
                            <Play className="h-4 w-4 mr-2" />
                            {runAnalysisMutation.isPending ? "Running..." : "Run Analysis"}
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation();
                              // TODO: Implement export functionality
                            }}
                          >
                            <Download className="h-4 w-4 mr-2" />
                            Export Results
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDelete(comparison.id);
                            }}
                            className="text-red-600"
                          >
                            <Trash2 className="h-4 w-4 mr-2" />
                            Delete
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Pagination */}
      {data && data.total > 10 && (
        <div className="flex items-center justify-between">
          <div className="comet-body-s text-muted-foreground">
            Showing {((page - 1) * 10) + 1} to {Math.min(page * 10, data.total)} of {data.total} comparisons
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage(page - 1)}
              disabled={page === 1}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setPage(page + 1)}
              disabled={page * 10 >= data.total}
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </div>
  );
};

export default ModelComparisonsList;
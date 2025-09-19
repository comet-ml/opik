import React, { useMemo } from "react";
import { BarChart3, TrendingUp, DollarSign, Target, Clock, CheckCircle, XCircle } from "lucide-react";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { ModelComparison } from "@/api/model-comparisons/useModelComparisonsList";
import { useRunModelComparisonAnalysis } from "@/api/model-comparisons/useRunModelComparisonAnalysis";

interface ModelComparisonOverviewProps {
  comparison: ModelComparison;
}

const ModelComparisonOverview: React.FunctionComponent<ModelComparisonOverviewProps> = ({
  comparison,
}) => {
  const runAnalysisMutation = useRunModelComparisonAnalysis();

  const handleRunAnalysis = () => {
    runAnalysisMutation.mutate(comparison.id);
  };

  const hasResults = comparison.results && comparison.results.model_performances.length > 0;

  const summaryStats = useMemo(() => {
    if (!hasResults) return null;

    const { model_performances, cost_comparison, accuracy_comparison } = comparison.results!;
    
    const totalCost = model_performances.reduce((sum, model) => sum + model.total_cost, 0);
    const avgAccuracy = accuracy_comparison.overall_scores 
      ? Object.values(accuracy_comparison.overall_scores).reduce((sum, score) => sum + score, 0) / Object.keys(accuracy_comparison.overall_scores).length
      : 0;
    const avgLatency = model_performances.reduce((sum, model) => sum + model.average_latency, 0) / model_performances.length;
    const avgSuccessRate = model_performances.reduce((sum, model) => sum + model.success_rate, 0) / model_performances.length;

    return {
      totalCost,
      avgAccuracy,
      avgLatency,
      avgSuccessRate,
      modelCount: model_performances.length,
    };
  }, [hasResults, comparison.results]);

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      {summaryStats && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="comet-title-s">Total Cost</CardTitle>
              <DollarSign className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="comet-title-l">${summaryStats.totalCost.toFixed(2)}</div>
              <p className="comet-body-xs text-muted-foreground">
                Across {summaryStats.modelCount} models
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="comet-title-s">Avg Accuracy</CardTitle>
              <Target className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="comet-title-l">{(summaryStats.avgAccuracy * 100).toFixed(1)}%</div>
              <Progress value={summaryStats.avgAccuracy * 100} className="mt-2" />
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="comet-title-s">Avg Latency</CardTitle>
              <Clock className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="comet-title-l">{summaryStats.avgLatency.toFixed(0)}ms</div>
              <p className="comet-body-xs text-muted-foreground">
                Response time
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="comet-title-s">Success Rate</CardTitle>
              <CheckCircle className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="comet-title-l">{(summaryStats.avgSuccessRate * 100).toFixed(1)}%</div>
              <Progress value={summaryStats.avgSuccessRate * 100} className="mt-2" />
            </CardContent>
          </Card>
        </div>
      )}

      {/* Action Bar */}
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <Badge variant={hasResults ? "default" : "secondary"}>
              {hasResults ? "Analysis Complete" : "No Analysis"}
            </Badge>
            <span className="comet-body-s text-muted-foreground">
              Last updated: {new Date(comparison.last_updated_at).toLocaleDateString()}
            </span>
          </div>
          <Button 
            onClick={handleRunAnalysis} 
            disabled={runAnalysisMutation.isPending}
            className="flex items-center gap-2"
          >
            <BarChart3 className="h-4 w-4" />
            {runAnalysisMutation.isPending ? "Running Analysis..." : "Run Analysis"}
          </Button>
        </div>
      </PageBodyStickyContainer>

      {!hasResults ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12">
            <BarChart3 className="h-12 w-12 text-muted-foreground mb-4" />
            <h3 className="comet-title-m mb-2">No Analysis Results</h3>
            <p className="comet-body text-muted-foreground text-center mb-6">
              Run analysis to compare model performance, costs, and accuracy metrics.
            </p>
            <Button onClick={handleRunAnalysis} disabled={runAnalysisMutation.isPending}>
              {runAnalysisMutation.isPending ? "Running Analysis..." : "Run Analysis"}
            </Button>
          </CardContent>
        </Card>
      ) : (
        <Tabs defaultValue="overview" className="space-y-6">
          <PageBodyStickyContainer direction="horizontal" limitWidth>
            <TabsList>
              <TabsTrigger value="overview">Overview</TabsTrigger>
              <TabsTrigger value="cost">Cost Analysis</TabsTrigger>
              <TabsTrigger value="accuracy">Accuracy</TabsTrigger>
              <TabsTrigger value="performance">Performance</TabsTrigger>
              <TabsTrigger value="datasets">Datasets</TabsTrigger>
            </TabsList>
          </PageBodyStickyContainer>

          <TabsContent value="overview">
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* Model Performance Summary */}
              <Card>
                <CardHeader>
                  <CardTitle>Model Performance Summary</CardTitle>
                  <CardDescription>
                    Key metrics across all models
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="space-y-4">
                    {comparison.results!.model_performances.map((model) => (
                      <div key={model.model_id} className="flex items-center justify-between p-3 border rounded-lg">
                        <div>
                          <h4 className="comet-title-s">{model.model_name}</h4>
                          <p className="comet-body-xs text-muted-foreground">{model.provider}</p>
                        </div>
                        <div className="text-right">
                          <div className="comet-body-s">${model.total_cost.toFixed(2)}</div>
                          <div className="comet-body-xs text-muted-foreground">
                            {(model.success_rate * 100).toFixed(1)}% success
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </CardContent>
              </Card>

              {/* Best Performing Models */}
              <Card>
                <CardHeader>
                  <CardTitle>Best Performing Models</CardTitle>
                  <CardDescription>
                    Top performers by category
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <DollarSign className="h-4 w-4 text-green-600" />
                        <span className="comet-body-s">Most Cost Effective</span>
                      </div>
                      <Badge variant="outline">
                        {comparison.results!.cost_comparison.most_cost_effective_model}
                      </Badge>
                    </div>
                    
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Target className="h-4 w-4 text-blue-600" />
                        <span className="comet-body-s">Best Accuracy</span>
                      </div>
                      <Badge variant="outline">
                        {comparison.results!.accuracy_comparison.best_performing_model}
                      </Badge>
                    </div>
                    
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Clock className="h-4 w-4 text-orange-600" />
                        <span className="comet-body-s">Fastest</span>
                      </div>
                      <Badge variant="outline">
                        {comparison.results!.performance_comparison.fastest_model}
                      </Badge>
                    </div>
                    
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <CheckCircle className="h-4 w-4 text-green-600" />
                        <span className="comet-body-s">Most Reliable</span>
                      </div>
                      <Badge variant="outline">
                        {comparison.results!.performance_comparison.most_reliable_model}
                      </Badge>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </div>
          </TabsContent>

          <TabsContent value="cost">
            <Card>
              <CardHeader>
                <CardTitle>Cost Analysis</CardTitle>
                <CardDescription>
                  Detailed cost breakdown and comparison
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-6">
                  {comparison.results!.cost_comparison.model_costs.map((cost) => (
                    <div key={cost.model_id} className="p-4 border rounded-lg">
                      <div className="flex items-center justify-between mb-3">
                        <h4 className="comet-title-s">{cost.model_name}</h4>
                        <Badge variant="outline">${cost.total_cost.toFixed(2)}</Badge>
                      </div>
                      <div className="grid grid-cols-3 gap-4 text-sm">
                        <div>
                          <span className="text-muted-foreground">Per Request:</span>
                          <div className="comet-body-s">${cost.cost_per_request.toFixed(4)}</div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">Per Token:</span>
                          <div className="comet-body-s">${cost.cost_per_token.toFixed(6)}</div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">Total Tokens:</span>
                          <div className="comet-body-s">{cost.token_usage.total_tokens.toLocaleString()}</div>
                        </div>
                      </div>
                    </div>
                  ))}
                  
                  <div className="p-4 bg-muted rounded-lg">
                    <h4 className="comet-title-s mb-2">Cost Efficiency</h4>
                    <p className="comet-body-s">
                      The most cost-effective model is <strong>{comparison.results!.cost_comparison.most_cost_effective_model}</strong>.
                      Total cost difference across models: <strong>${comparison.results!.cost_comparison.total_cost_difference.toFixed(2)}</strong>.
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="accuracy">
            <Card>
              <CardHeader>
                <CardTitle>Accuracy Comparison</CardTitle>
                <CardDescription>
                  Model performance across different evaluation metrics
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-6">
                  {comparison.results!.accuracy_comparison.metric_comparisons.map((metric) => (
                    <div key={metric.metric_name} className="p-4 border rounded-lg">
                      <div className="flex items-center justify-between mb-3">
                        <div>
                          <h4 className="comet-title-s">{metric.metric_name}</h4>
                          <p className="comet-body-xs text-muted-foreground">{metric.metric_category}</p>
                        </div>
                        <Badge variant="outline">{metric.best_model}</Badge>
                      </div>
                      <div className="space-y-2">
                        {metric.model_scores.map((score) => (
                          <div key={score.model_id} className="flex items-center justify-between">
                            <span className="comet-body-s">{score.model_name}</span>
                            <div className="flex items-center gap-2">
                              <Progress value={score.score * 100} className="w-20" />
                              <span className="comet-body-s w-12 text-right">
                                {(score.score * 100).toFixed(1)}%
                              </span>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="performance">
            <Card>
              <CardHeader>
                <CardTitle>Performance Metrics</CardTitle>
                <CardDescription>
                  Latency, success rates, and reliability metrics
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  {comparison.results!.performance_comparison.model_metrics.map((metrics) => (
                    <div key={metrics.model_id} className="p-4 border rounded-lg">
                      <div className="flex items-center justify-between mb-3">
                        <h4 className="comet-title-s">{metrics.model_name}</h4>
                        <div className="flex gap-2">
                          <Badge variant={metrics.success_rate > 0.95 ? "default" : "secondary"}>
                            {(metrics.success_rate * 100).toFixed(1)}% success
                          </Badge>
                        </div>
                      </div>
                      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                        <div>
                          <span className="text-muted-foreground">Avg Latency:</span>
                          <div className="comet-body-s">{metrics.average_latency.toFixed(0)}ms</div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">P95 Latency:</span>
                          <div className="comet-body-s">{metrics.p95_latency.toFixed(0)}ms</div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">Total Requests:</span>
                          <div className="comet-body-s">{metrics.total_requests.toLocaleString()}</div>
                        </div>
                        <div>
                          <span className="text-muted-foreground">Failed Requests:</span>
                          <div className="comet-body-s text-red-600">{metrics.failed_requests.toLocaleString()}</div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="datasets">
            <div className="space-y-6">
              {comparison.results!.dataset_comparisons.map((dataset) => (
                <Card key={dataset.dataset_name}>
                  <CardHeader>
                    <CardTitle>{dataset.dataset_name}</CardTitle>
                    <CardDescription>
                      {dataset.total_items} items • Best model: {dataset.best_model}
                    </CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="space-y-4">
                      {dataset.model_performances.map((performance) => (
                        <div key={performance.model_id} className="flex items-center justify-between p-3 border rounded-lg">
                          <div>
                            <h4 className="comet-title-s">{performance.model_name}</h4>
                            <p className="comet-body-xs text-muted-foreground">
                              {performance.items_processed} items processed
                            </p>
                          </div>
                          <div className="text-right">
                            <div className="comet-body-s">
                              Avg Score: {(performance.average_score * 100).toFixed(1)}%
                            </div>
                            <div className="comet-body-xs text-muted-foreground">
                              ${performance.total_cost.toFixed(2)}
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
};

export default ModelComparisonOverview;
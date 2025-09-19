import React, { useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Plus, BarChart3, TrendingUp, DollarSign, Target } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import ModelComparisonsList from "./ModelComparisonsList/ModelComparisonsList";
import CreateModelComparisonDialog from "./CreateModelComparisonDialog/CreateModelComparisonDialog";
import ModelComparisonOverview from "./ModelComparisonOverview/ModelComparisonOverview";
import { ModelComparison } from "@/api/model-comparisons/useModelComparisonsList";

const ModelComparisonAdminPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false);
  const [selectedComparison, setSelectedComparison] = useState<ModelComparison | null>(null);

  const handleCreateComparison = () => {
    setIsCreateDialogOpen(true);
  };

  const handleComparisonSelect = (comparison: ModelComparison) => {
    setSelectedComparison(comparison);
  };

  const handleBackToList = () => {
    setSelectedComparison(null);
  };

  if (selectedComparison) {
    return (
      <PageBodyScrollContainer>
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <div className="flex items-center justify-between">
            <div>
              <Button variant="ghost" onClick={handleBackToList} className="mb-4">
                ← Back to Comparisons
              </Button>
              <h1 className="comet-title-xl">{selectedComparison.name}</h1>
              {selectedComparison.description && (
                <p className="comet-body text-muted-foreground mt-2">
                  {selectedComparison.description}
                </p>
              )}
            </div>
          </div>
        </PageBodyStickyContainer>
        <ModelComparisonOverview comparison={selectedComparison} />
      </PageBodyScrollContainer>
    );
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <div className="flex items-center justify-between">
          <div>
            <h1 className="comet-title-xl">Model Comparison Admin</h1>
            <p className="comet-body text-muted-foreground mt-2">
              Compare LLM models across accuracy, cost, and performance metrics
            </p>
          </div>
          <Button onClick={handleCreateComparison} className="flex items-center gap-2">
            <Plus className="h-4 w-4" />
            Create Comparison
          </Button>
        </div>
      </PageBodyStickyContainer>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="comet-title-s">Total Comparisons</CardTitle>
            <BarChart3 className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="comet-title-l">12</div>
            <p className="comet-body-xs text-muted-foreground">
              +2 from last month
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="comet-title-s">Models Analyzed</CardTitle>
            <Target className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="comet-title-l">8</div>
            <p className="comet-body-xs text-muted-foreground">
              Across 5 providers
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="comet-title-s">Cost Savings</CardTitle>
            <DollarSign className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="comet-title-l">$2,340</div>
            <p className="comet-body-xs text-muted-foreground">
              -15% vs baseline
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="comet-title-s">Avg Accuracy</CardTitle>
            <TrendingUp className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="comet-title-l">87.3%</div>
            <p className="comet-body-xs text-muted-foreground">
              +3.2% improvement
            </p>
          </CardContent>
        </Card>
      </div>

      <Tabs defaultValue="comparisons" className="space-y-6">
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList>
            <TabsTrigger value="comparisons">Model Comparisons</TabsTrigger>
            <TabsTrigger value="analytics">Analytics</TabsTrigger>
            <TabsTrigger value="insights">Insights</TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>

        <TabsContent value="comparisons">
          <ModelComparisonsList onComparisonSelect={handleComparisonSelect} />
        </TabsContent>

        <TabsContent value="analytics">
          <div className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle>Model Performance Trends</CardTitle>
                <CardDescription>
                  Track model performance over time across different metrics
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="h-64 flex items-center justify-center text-muted-foreground">
                  Performance trends chart will be implemented here
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Cost Analysis</CardTitle>
                <CardDescription>
                  Compare costs across different models and providers
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="h-64 flex items-center justify-center text-muted-foreground">
                  Cost analysis chart will be implemented here
                </div>
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        <TabsContent value="insights">
          <div className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle>Key Insights</CardTitle>
                <CardDescription>
                  AI-generated insights from your model comparison data
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  <div className="p-4 bg-blue-50 dark:bg-blue-950/20 rounded-lg">
                    <h4 className="comet-title-s text-blue-900 dark:text-blue-100">
                      Cost Optimization Opportunity
                    </h4>
                    <p className="comet-body-s text-blue-700 dark:text-blue-300 mt-1">
                      Switching from GPT-4 to GPT-3.5-turbo for simple tasks could reduce costs by 65% 
                      while maintaining 92% accuracy.
                    </p>
                  </div>
                  
                  <div className="p-4 bg-green-50 dark:bg-green-950/20 rounded-lg">
                    <h4 className="comet-title-s text-green-900 dark:text-green-100">
                      Performance Improvement
                    </h4>
                    <p className="comet-body-s text-green-700 dark:text-green-300 mt-1">
                      Claude-3-sonnet shows 15% better performance on reasoning tasks compared to other models.
                    </p>
                  </div>
                  
                  <div className="p-4 bg-orange-50 dark:bg-orange-950/20 rounded-lg">
                    <h4 className="comet-title-s text-orange-900 dark:text-orange-100">
                      Reliability Alert
                    </h4>
                    <p className="comet-body-s text-orange-700 dark:text-orange-300 mt-1">
                      Model X shows higher error rates during peak hours. Consider load balancing.
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        </TabsContent>
      </Tabs>

      <CreateModelComparisonDialog
        open={isCreateDialogOpen}
        onOpenChange={setIsCreateDialogOpen}
      />
    </PageBodyScrollContainer>
  );
};

export default ModelComparisonAdminPage;
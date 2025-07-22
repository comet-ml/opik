import React, { useState } from 'react';
import { useParams, useNavigate } from '@tanstack/react-router';
import { Plus, Eye, Edit, Trash2, Copy, Download, Upload } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { useToast } from '@/components/ui/use-toast';
import { useDashboards, useCreateDashboard, useDashboardActions } from '@/hooks/useDashboard';
import DashboardBuilder from '@/components/dashboard/DashboardBuilder';
import DashboardRenderer from '@/components/dashboard/DashboardRenderer';
import { Dashboard } from '@/types/dashboard';
import { formatTimestamp } from '@/utils/chartHelpers';

const DashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { data: dashboards, isLoading } = useDashboards();
  const createDashboard = useCreateDashboard();
  
  const [currentView, setCurrentView] = useState<'list' | 'builder' | 'viewer'>('list');
  const [selectedDashboard, setSelectedDashboard] = useState<Dashboard | null>(null);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newDashboardName, setNewDashboardName] = useState('');
  const [newDashboardDescription, setNewDashboardDescription] = useState('');
  const [isFullscreen, setIsFullscreen] = useState(false);

  const { 
    updateDashboard, 
    deleteDashboard, 
    duplicateDashboard,
    isUpdating,
    isDeleting,
    isDuplicating 
  } = useDashboardActions(selectedDashboard?.id);

  const handleCreateDashboard = async () => {
    try {
      const result = await createDashboard.mutateAsync({
        name: newDashboardName,
        description: newDashboardDescription,
      });
      
      setSelectedDashboard(result.data);
      setCurrentView('builder');
      setIsCreateModalOpen(false);
      setNewDashboardName('');
      setNewDashboardDescription('');
      
      toast({
        title: 'Dashboard created',
        description: 'Your new dashboard has been created successfully.',
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to create dashboard. Please try again.',
        variant: 'destructive',
      });
    }
  };

  const handleEditDashboard = (dashboard: Dashboard) => {
    setSelectedDashboard(dashboard);
    setCurrentView('builder');
  };

  const handleViewDashboard = (dashboard: Dashboard) => {
    setSelectedDashboard(dashboard);
    setCurrentView('viewer');
  };

  const handleDeleteDashboard = async (dashboard: Dashboard) => {
    if (window.confirm('Are you sure you want to delete this dashboard?')) {
      try {
        await deleteDashboard();
        toast({
          title: 'Dashboard deleted',
          description: 'Dashboard has been deleted successfully.',
        });
      } catch (error) {
        toast({
          title: 'Error',
          description: 'Failed to delete dashboard. Please try again.',
          variant: 'destructive',
        });
      }
    }
  };

  const handleDuplicateDashboard = async (dashboard: Dashboard) => {
    try {
      await duplicateDashboard(`${dashboard.name} (Copy)`);
      toast({
        title: 'Dashboard duplicated',
        description: 'Dashboard has been duplicated successfully.',
      });
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to duplicate dashboard. Please try again.',
        variant: 'destructive',
      });
    }
  };

  const handleSaveDashboard = async (dashboardData: Partial<Dashboard>) => {
    if (!selectedDashboard) return;
    
    try {
      await updateDashboard(dashboardData);
      // Update local state
      setSelectedDashboard(prev => prev ? { ...prev, ...dashboardData } : null);
    } catch (error) {
      toast({
        title: 'Error',
        description: 'Failed to save dashboard. Please try again.',
        variant: 'destructive',
      });
    }
  };

  const handleBackToList = () => {
    setCurrentView('list');
    setSelectedDashboard(null);
    setIsFullscreen(false);
  };

  // Dashboard List View
  if (currentView === 'list') {
    return (
      <div className="container mx-auto p-6">
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-3xl font-bold">Dashboards</h1>
            <p className="text-muted-foreground mt-1">
              Create and manage your custom dashboards
            </p>
          </div>
          
          <Dialog open={isCreateModalOpen} onOpenChange={setIsCreateModalOpen}>
            <DialogTrigger asChild>
              <Button>
                <Plus className="h-4 w-4 mr-2" />
                Create Dashboard
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Create New Dashboard</DialogTitle>
              </DialogHeader>
              <div className="space-y-4">
                <div>
                  <Label htmlFor="name">Dashboard Name</Label>
                  <Input
                    id="name"
                    value={newDashboardName}
                    onChange={(e) => setNewDashboardName(e.target.value)}
                    placeholder="Enter dashboard name"
                  />
                </div>
                <div>
                  <Label htmlFor="description">Description (optional)</Label>
                  <Textarea
                    id="description"
                    value={newDashboardDescription}
                    onChange={(e) => setNewDashboardDescription(e.target.value)}
                    placeholder="Enter dashboard description"
                    rows={3}
                  />
                </div>
                <div className="flex justify-end space-x-2">
                  <Button
                    variant="outline"
                    onClick={() => setIsCreateModalOpen(false)}
                  >
                    Cancel
                  </Button>
                  <Button
                    onClick={handleCreateDashboard}
                    disabled={!newDashboardName.trim() || createDashboard.isPending}
                  >
                    {createDashboard.isPending ? 'Creating...' : 'Create'}
                  </Button>
                </div>
              </div>
            </DialogContent>
          </Dialog>
        </div>

        {isLoading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <Card key={i} className="animate-pulse">
                <CardHeader>
                  <div className="h-6 bg-muted rounded w-3/4"></div>
                  <div className="h-4 bg-muted rounded w-1/2"></div>
                </CardHeader>
                <CardContent>
                  <div className="h-4 bg-muted rounded w-full mb-2"></div>
                  <div className="h-4 bg-muted rounded w-2/3"></div>
                </CardContent>
              </Card>
            ))}
          </div>
        ) : dashboards?.data && dashboards.data.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {dashboards.data.map((dashboard) => (
              <Card key={dashboard.id} className="hover:shadow-lg transition-shadow duration-200">
                <CardHeader>
                  <div className="flex items-start justify-between">
                    <div className="flex-1 min-w-0">
                      <CardTitle className="truncate">{dashboard.name}</CardTitle>
                      {dashboard.description && (
                        <p className="text-sm text-muted-foreground mt-1 line-clamp-2">
                          {dashboard.description}
                        </p>
                      )}
                    </div>
                  </div>
                </CardHeader>
                <CardContent>
                  <div className="flex items-center justify-between text-sm text-muted-foreground mb-4">
                    <span>{dashboard.layout.grid.length} widgets</span>
                    <span>Modified {formatTimestamp(dashboard.modified)}</span>
                  </div>
                  
                  <div className="flex items-center space-x-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleViewDashboard(dashboard)}
                    >
                      <Eye className="h-4 w-4 mr-1" />
                      View
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleEditDashboard(dashboard)}
                    >
                      <Edit className="h-4 w-4 mr-1" />
                      Edit
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleDuplicateDashboard(dashboard)}
                      disabled={isDuplicating}
                    >
                      <Copy className="h-4 w-4 mr-1" />
                      Duplicate
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleDeleteDashboard(dashboard)}
                      disabled={isDeleting}
                      className="text-destructive hover:text-destructive"
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        ) : (
          <div className="text-center py-12">
            <h3 className="text-lg font-semibold mb-2">No dashboards yet</h3>
            <p className="text-muted-foreground mb-4">
              Create your first dashboard to get started
            </p>
            <Button onClick={() => setIsCreateModalOpen(true)}>
              <Plus className="h-4 w-4 mr-2" />
              Create Your First Dashboard
            </Button>
          </div>
        )}
      </div>
    );
  }

  // Dashboard Builder View
  if (currentView === 'builder' && selectedDashboard) {
    return (
      <div className="h-screen">
        <DashboardBuilder
          dashboard={selectedDashboard}
          onSave={handleSaveDashboard}
          onPreview={() => setCurrentView('viewer')}
        />
        <div className="absolute top-4 left-4 z-10">
          <Button variant="outline" onClick={handleBackToList}>
            ← Back to Dashboards
          </Button>
        </div>
      </div>
    );
  }

  // Dashboard Viewer View
  if (currentView === 'viewer' && selectedDashboard) {
    return (
      <>
        <DashboardRenderer
          dashboard={selectedDashboard}
          isFullscreen={isFullscreen}
          onToggleFullscreen={() => setIsFullscreen(!isFullscreen)}
        />
        {!isFullscreen && (
          <div className="absolute top-4 left-4 z-10">
            <div className="flex items-center space-x-2">
              <Button variant="outline" onClick={handleBackToList}>
                ← Back to Dashboards
              </Button>
              <Button variant="outline" onClick={() => setCurrentView('builder')}>
                <Edit className="h-4 w-4 mr-2" />
                Edit Dashboard
              </Button>
            </div>
          </div>
        )}
      </>
    );
  }

  return null;
};

export default DashboardPage;

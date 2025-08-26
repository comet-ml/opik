import React, { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
// Progress component - using a simple div for now
import { Separator } from "@/components/ui/separator";
import { 
  ArrowRight, 
  ArrowLeft, 
  SkipForward, 
  CheckCircle, 
  Clock,
  Star,
  MessageSquare,
  Keyboard,
  X
} from "lucide-react";
import { toast } from "@/components/ui/use-toast";
import usePublicAnnotationQueue from "@/api/annotationQueues/usePublicAnnotationQueue";
import useNextAnnotationItem from "@/api/annotationQueues/useNextAnnotationItem";
import useSubmitAnnotationMutation from "@/api/annotationQueues/useSubmitAnnotationMutation";
import useUpdateItemStatusMutation from "@/api/annotationQueues/useUpdateItemStatusMutation";

const AnnotationPage: React.FC = () => {
  const { queueId } = useParams({ from: "/annotation/$queueId" });
  const navigate = useNavigate();
  
  // SME identification - in a real app, this would come from authentication
  const [smeId, setSmeId] = useState<string>("");
  const [showSmeInput, setShowSmeInput] = useState(true);
  
  // Annotation form state
  const [rating, setRating] = useState<number>(0);
  const [comment, setComment] = useState<string>("");
  const [showKeyboardHelp, setShowKeyboardHelp] = useState(false);

  // API hooks
  const { data: queue, isLoading: queueLoading } = usePublicAnnotationQueue({ queueId });
  const { data: currentItem, isLoading: itemLoading, refetch: refetchNextItem } = useNextAnnotationItem({ 
    queueId, 
    smeId 
  });
  const submitAnnotation = useSubmitAnnotationMutation();
  const updateItemStatus = useUpdateItemStatusMutation();

  // Calculate progress
  const progress = queue ? (queue.completed_items / queue.total_items) * 100 : 0;
  const remainingItems = queue ? queue.total_items - queue.completed_items : 0;

  // Reset form when item changes
  useEffect(() => {
    if (currentItem) {
      setRating(0);
      setComment("");
    }
  }, [currentItem?.id]);

  // Keyboard shortcuts
  const handleKeyPress = useCallback((event: KeyboardEvent) => {
    if (showSmeInput || !currentItem) return;

    switch (event.key) {
      case "1":
      case "2":
      case "3":
      case "4":
      case "5":
        event.preventDefault();
        setRating(parseInt(event.key));
        break;
      case "Enter":
        if (event.ctrlKey || event.metaKey) {
          event.preventDefault();
          handleSubmitAnnotation();
        }
        break;
      case "s":
        if (event.ctrlKey || event.metaKey) {
          event.preventDefault();
          handleSkipItem();
        }
        break;
      case "?":
        event.preventDefault();
        setShowKeyboardHelp(!showKeyboardHelp);
        break;
      case "Escape":
        event.preventDefault();
        if (showKeyboardHelp) {
          setShowKeyboardHelp(false);
        }
        break;
    }
  }, [showSmeInput, currentItem, showKeyboardHelp, rating, comment]);

  useEffect(() => {
    document.addEventListener("keydown", handleKeyPress);
    return () => document.removeEventListener("keydown", handleKeyPress);
  }, [handleKeyPress]);

  const handleSmeIdSubmit = () => {
    if (smeId.trim()) {
      setShowSmeInput(false);
    }
  };

  const handleSubmitAnnotation = async () => {
    if (!currentItem || !smeId || rating === 0) {
      toast({
        title: "Missing Information",
        description: "Please provide a rating before submitting.",
        variant: "destructive",
      });
      return;
    }

    try {
      await submitAnnotation.mutateAsync({
        queueId,
        itemId: currentItem.id,
        smeId,
        metrics: { rating },
        comment: comment.trim() || undefined,
      });

      toast({
        title: "Annotation Submitted",
        description: "Your annotation has been saved successfully.",
      });

      // Reset form and get next item
      setRating(0);
      setComment("");
      refetchNextItem();
    } catch (error) {
      toast({
        title: "Submission Failed",
        description: "Failed to submit annotation. Please try again.",
        variant: "destructive",
      });
    }
  };

  const handleSkipItem = async () => {
    if (!currentItem || !smeId) return;

    try {
      await updateItemStatus.mutateAsync({
        queueId,
        itemId: currentItem.id,
        smeId,
        status: "skipped",
      });

      toast({
        title: "Item Skipped",
        description: "Item has been skipped.",
      });

      // Reset form and get next item
      setRating(0);
      setComment("");
      refetchNextItem();
    } catch (error) {
      toast({
        title: "Skip Failed",
        description: "Failed to skip item. Please try again.",
        variant: "destructive",
      });
    }
  };

  const renderStarRating = () => (
    <div className="flex items-center gap-2">
      <Label>Rating (1-5 stars):</Label>
      <div className="flex gap-1">
        {[1, 2, 3, 4, 5].map((star) => (
          <Button
            key={star}
            variant={rating >= star ? "default" : "outline"}
            size="sm"
            onClick={() => setRating(star)}
            className="w-8 h-8 p-0"
          >
            <Star className={`w-4 h-4 ${rating >= star ? "fill-current" : ""}`} />
          </Button>
        ))}
      </div>
      <span className="text-sm text-muted-foreground ml-2">
        Press {rating > 0 ? rating : "1-5"} for quick rating
      </span>
    </div>
  );

  const renderKeyboardHelp = () => (
    showKeyboardHelp && (
      <Card className="fixed top-4 right-4 w-80 z-50 shadow-lg">
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-sm">Keyboard Shortcuts</CardTitle>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setShowKeyboardHelp(false)}
              className="w-6 h-6 p-0"
            >
              <X className="w-4 h-4" />
            </Button>
          </div>
        </CardHeader>
        <CardContent className="text-sm space-y-2">
          <div className="flex justify-between">
            <span>Rate item:</span>
            <Badge variant="outline">1-5</Badge>
          </div>
          <div className="flex justify-between">
            <span>Submit:</span>
            <Badge variant="outline">Ctrl+Enter</Badge>
          </div>
          <div className="flex justify-between">
            <span>Skip item:</span>
            <Badge variant="outline">Ctrl+S</Badge>
          </div>
          <div className="flex justify-between">
            <span>Help:</span>
            <Badge variant="outline">?</Badge>
          </div>
        </CardContent>
      </Card>
    )
  );

  if (queueLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <Clock className="w-8 h-8 animate-spin mx-auto mb-4" />
          <p>Loading annotation queue...</p>
        </div>
      </div>
    );
  }

  if (!queue) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <p className="text-lg mb-4">Queue not found</p>
          <Button onClick={() => navigate({ to: "/" })}>
            Go Home
          </Button>
        </div>
      </div>
    );
  }

  if (showSmeInput) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>Annotation Access</CardTitle>
            <p className="text-sm text-muted-foreground">
              Please enter your SME identifier to begin annotation
            </p>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <Label htmlFor="smeId">SME ID</Label>
              <input
                id="smeId"
                type="text"
                value={smeId}
                onChange={(e) => setSmeId(e.target.value)}
                onKeyPress={(e) => e.key === "Enter" && handleSmeIdSubmit()}
                className="w-full mt-1 px-3 py-2 border rounded-md"
                placeholder="Enter your SME identifier"
                autoFocus
              />
            </div>
            <Button onClick={handleSmeIdSubmit} className="w-full" disabled={!smeId.trim()}>
              Start Annotation
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!currentItem && !itemLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <CheckCircle className="w-16 h-16 text-green-500 mx-auto mb-4" />
          <h2 className="text-2xl font-bold mb-2">All Done!</h2>
          <p className="text-muted-foreground mb-4">
            No more items available for annotation in this queue.
          </p>
          <Button onClick={() => navigate({ to: "/" })}>
            Return Home
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <div className="border-b bg-card">
        <div className="container mx-auto px-4 py-4">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-semibold">{queue.name}</h1>
              <p className="text-sm text-muted-foreground">
                {remainingItems} items remaining
              </p>
            </div>
            <div className="flex items-center gap-4">
              <div className="text-right">
                <div className="text-sm font-medium">{Math.round(progress)}% Complete</div>
                <div className="w-32 h-2 bg-gray-200 rounded-full overflow-hidden">
                  <div 
                    className="h-full bg-blue-500 transition-all duration-300" 
                    style={{ width: `${progress}%` }}
                  />
                </div>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowKeyboardHelp(!showKeyboardHelp)}
              >
                <Keyboard className="w-4 h-4 mr-2" />
                Shortcuts
              </Button>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="container mx-auto px-4 py-8">
        {itemLoading ? (
          <div className="text-center">
            <Clock className="w-8 h-8 animate-spin mx-auto mb-4" />
            <p>Loading next item...</p>
          </div>
        ) : currentItem ? (
          <div className="max-w-4xl mx-auto space-y-6">
            {/* Item Content */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Badge variant="outline">{currentItem.item_type}</Badge>
                  Item for Annotation
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                {/* Display trace/thread data */}
                {currentItem.trace_data && (
                  <div className="space-y-3">
                    {currentItem.trace_data.name && (
                      <div>
                        <Label className="text-sm font-medium">Name:</Label>
                        <p className="mt-1">{currentItem.trace_data.name}</p>
                      </div>
                    )}
                    
                    {currentItem.trace_data.input && (
                      <div>
                        <Label className="text-sm font-medium">Input:</Label>
                        <pre className="mt-1 p-3 bg-muted rounded-md text-sm overflow-auto">
                          {JSON.stringify(currentItem.trace_data.input, null, 2)}
                        </pre>
                      </div>
                    )}
                    
                    {currentItem.trace_data.output && (
                      <div>
                        <Label className="text-sm font-medium">Output:</Label>
                        <pre className="mt-1 p-3 bg-muted rounded-md text-sm overflow-auto">
                          {JSON.stringify(currentItem.trace_data.output, null, 2)}
                        </pre>
                      </div>
                    )}
                  </div>
                )}

                {currentItem.thread_data && (
                  <div className="space-y-3">
                    {currentItem.thread_data.name && (
                      <div>
                        <Label className="text-sm font-medium">Thread Name:</Label>
                        <p className="mt-1">{currentItem.thread_data.name}</p>
                      </div>
                    )}
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Annotation Form */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <MessageSquare className="w-5 h-5" />
                  Your Annotation
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                {/* Rating */}
                {renderStarRating()}

                <Separator />

                {/* Comment */}
                <div>
                  <Label htmlFor="comment">Comment (optional):</Label>
                  <Textarea
                    id="comment"
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder="Add any additional notes or feedback..."
                    className="mt-1 min-h-[100px]"
                  />
                </div>

                {/* Actions */}
                <div className="flex items-center justify-between pt-4">
                  <Button
                    variant="outline"
                    onClick={handleSkipItem}
                    disabled={updateItemStatus.isPending}
                  >
                    <SkipForward className="w-4 h-4 mr-2" />
                    Skip (Ctrl+S)
                  </Button>

                  <Button
                    onClick={handleSubmitAnnotation}
                    disabled={rating === 0 || submitAnnotation.isPending}
                    className="min-w-[140px]"
                  >
                    {submitAnnotation.isPending ? (
                      <Clock className="w-4 h-4 mr-2 animate-spin" />
                    ) : (
                      <CheckCircle className="w-4 h-4 mr-2" />
                    )}
                    Submit (Ctrl+Enter)
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        ) : null}
      </div>

      {/* Keyboard Help Overlay */}
      {renderKeyboardHelp()}
    </div>
  );
};

export default AnnotationPage;
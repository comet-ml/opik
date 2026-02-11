export class AnnotationQueueError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AnnotationQueueError";
  }
}

export class AnnotationQueueNotFoundError extends AnnotationQueueError {
  constructor(queueId: string) {
    super(`Annotation queue with ID '${queueId}' not found`);
    this.name = "AnnotationQueueNotFoundError";
  }
}

export class AnnotationQueueScopeMismatchError extends AnnotationQueueError {
  constructor(itemType: string, queueScope: string) {
    super(
      `Cannot add/remove ${itemType} to/from annotation queue with scope '${queueScope}'. ` +
        `Use a queue with scope '${itemType}' instead.`
    );
    this.name = "AnnotationQueueScopeMismatchError";
  }
}

export class AnnotationQueueItemMissingIdError extends AnnotationQueueError {
  constructor(itemType: string, index?: number) {
    const indexInfo = index !== undefined ? ` at index ${index}` : "";
    super(`${itemType} object${indexInfo} has no ID`);
    this.name = "AnnotationQueueItemMissingIdError";
  }
}

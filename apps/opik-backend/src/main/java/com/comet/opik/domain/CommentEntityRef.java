package com.comet.opik.domain;

import java.util.UUID;

public record CommentEntityRef(UUID commentId, UUID entityId, CommentDAO.EntityType entityType) {
}

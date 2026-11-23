package com.comet.opik.infrastructure.db;

import com.comet.opik.api.view.ViewAuthor;

public class ViewAuthorMapper extends AbstractEnumColumnMapper<ViewAuthor> {
    public ViewAuthorMapper() {
        super(ViewAuthor::fromString, "view_author");
    }
}

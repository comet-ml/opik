package com.comet.opik.domain;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;

public class FeedbackDefinitionRowMapper implements RowMapper<FeedbackDefinitionModel<?>> {

    @Override
    public FeedbackDefinitionModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {

        var feedbackType = FeedbackType.fromString(rs.getString("type"));

        return switch (feedbackType) {
            case NUMERICAL -> ctx.findMapperFor(NumericalFeedbackDefinitionDefinitionModel.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "No mapper found for Feedback Definition Type: %s".formatted(feedbackType)))
                    .map(rs, ctx);
            case CATEGORICAL -> ctx.findMapperFor(CategoricalFeedbackDefinitionDefinitionModel.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "No mapper found for Feedback Definition Type: %s".formatted(feedbackType)))
                    .map(rs, ctx);
            case BOOLEAN -> ctx.findMapperFor(BooleanFeedbackDefinitionDefinitionModel.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "No mapper found for Feedback Definition Type: %s".formatted(feedbackType)))
                    .map(rs, ctx);
        };
    }
}

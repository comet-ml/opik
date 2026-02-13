package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.error.ErrorMessage;
import io.r2dbc.spi.Statement;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Centralises tag-related operations shared across DAOs and services
 */
public class TagOperations {

    private TagOperations() {
    }

    /**
     * Interface for update DTOs that support tag operations
     */
    public interface TagUpdatable {
        Set<String> tags();
        Set<String> tagsToAdd();
        Set<String> tagsToRemove();
    }

    /**
     * Configures a StringTemplate with tag update parameters.
     * Handles both the new tagsToAdd/tagsToRemove API and the legacy tags + mergeTags API.
     * If both are provided, tagsToAdd/tagsToRemove takes precedence.
     *
     * @param template The StringTemplate to configure
     * @param update The update DTO containing tag fields
     * @param mergeTags Whether to merge tags (only used with legacy tags approach)
     */
    public static void configureTagTemplate(ST template, TagUpdatable update, boolean mergeTags) {
        if (update.tagsToAdd() != null || update.tagsToRemove() != null) {
            if (update.tagsToAdd() != null) {
                template.add("tags_to_add", true);
            }
            if (update.tagsToRemove() != null) {
                template.add("tags_to_remove", true);
            }
        } else if (update.tags() != null) {
            template.add("tags", update.tags().toString());
            template.add("merge_tags", mergeTags);
        }
    }

    /**
     * Binds tag parameters to a Statement.
     * Handles both the new tagsToAdd/tagsToRemove API and the legacy tags + mergeTags API.
     * If both are provided, tagsToAdd/tagsToRemove takes precedence.
     *
     * @param statement The Statement to bind parameters to
     * @param update The update DTO containing tag fields
     */
    public static void bindTagParams(Statement statement, TagUpdatable update) {
        if (update.tagsToAdd() != null || update.tagsToRemove() != null) {
            if (update.tagsToAdd() != null) {
                statement.bind("tags_to_add", update.tagsToAdd().toArray(String[]::new));
            }
            if (update.tagsToRemove() != null) {
                statement.bind("tags_to_remove", update.tagsToRemove().toArray(String[]::new));
            }
        } else if (update.tags() != null) {
            statement.bind("tags", update.tags().toArray(String[]::new));
        }
    }

    public static final int MAX_TAGS_PER_ITEM = 50;
    static final String TAG_LIMIT_ERROR = "Tag limit exceeded: maximum " + MAX_TAGS_PER_ITEM + " tags per item";

    /**
     * Maps a ClickHouse tag-limit throwIf error to a 422 response.
     * Use in reactive chains via {@code .onErrorResume(TagOperations::mapTagLimitError)}.
     */
    public static <T> Mono<T> mapTagLimitError(Throwable ex) {
        if (ex instanceof ClickHouseException && ex.getMessage() != null
                && ex.getMessage().contains(TAG_LIMIT_ERROR)) {
            return Mono.error(new ClientErrorException(
                    Response.status(422).entity(new ErrorMessage(List.of(TAG_LIMIT_ERROR))).build()));
        }
        return Mono.error(ex);
    }

    /**
     * Generates a StringTemplate fragment for handling tag updates in batch operations.
     * Supports both the new tagsToAdd/tagsToRemove API and the legacy tags + mergeTags API.
     *
     * <p>When new tags are added, the result is wrapped with a length check that throws if
     * the resulting tag array exceeds {@link #MAX_TAGS_PER_ITEM}. Removal-only operations
     * and non-tag updates skip validation since they cannot increase the count.
     *
     * <p>Requires {@code short_circuit_function_evaluation = 'force_enable'} in the query SETTINGS
     * to ensure throwIf is only evaluated when the limit is actually exceeded.
     *
     * @param tagsColumnRef The table alias + column name (e.g., "s.tags", "t.tags", "src.tags", "tags")
     * @return StringTemplate fragment for tag update logic
     */
    public static String tagUpdateFragment(String tagsColumnRef) {
        String innerGuarded = """
                <if(tags_to_add && tags_to_remove)>
                    arrayDistinct(arrayConcat(arrayFilter(x -> NOT has(:tags_to_remove, x), TAGS_COL), :tags_to_add))
                <elseif(tags_to_add)>
                    arrayDistinct(arrayConcat(TAGS_COL, :tags_to_add))
                <elseif(tags)>
                    <if(merge_tags)>arrayDistinct(arrayConcat(TAGS_COL, :tags))<else>:tags<endif>
                <endif>""";

        String guarded = "if(length(" + innerGuarded + ") > " + MAX_TAGS_PER_ITEM
                + ", [toString(throwIf(1, '" + TAG_LIMIT_ERROR + "'))]"
                + ", " + innerGuarded + ")";

        return """
                <if(tags_to_add || tags)>
                    """ + guarded + """
                <elseif(tags_to_remove)>
                    arrayFilter(x -> NOT has(:tags_to_remove, x), TAGS_COL)
                <else>
                    TAGS_COL
                <endif>"""
                .replace("TAGS_COL", tagsColumnRef);
    }
}

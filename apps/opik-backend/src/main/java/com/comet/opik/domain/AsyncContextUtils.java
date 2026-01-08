package com.comet.opik.domain;

import com.comet.opik.utils.AsyncUtils.ContextAwareAction;
import com.comet.opik.utils.AsyncUtils.ContextAwareStream;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@UtilityClass
public class AsyncContextUtils {

    public static ContextAwareStream<Result> bindWorkspaceIdToFlux(Statement statement) {
        return (userName, workspaceId) -> {
            statement.bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        };
    }

    public static ContextAwareAction<Result> bindWorkspaceIdToMono(Statement statement) {
        return (userName, workspaceId) -> {
            statement.bind("workspace_id", workspaceId);
            return Mono.from(statement.execute());
        };
    }

    public static ContextAwareAction<Result> bindUserNameAndWorkspaceContext(Statement statement) {
        return (userName, workspaceId) -> {
            statement.bind("user_name", userName);
            statement.bind("workspace_id", workspaceId);

            return Mono.from(statement.execute());
        };
    }

    public static void bindUserNameAndWorkspace(Statement statement, String userName, String workspaceId) {
        statement.bind("user_name", userName);
        statement.bind("workspace_id", workspaceId);
    }

    static ContextAwareStream<Result> bindUserNameAndWorkspaceContextToStream(
            Statement statement) {
        return (userName, workspaceId) -> {
            statement.bind("user_name", userName);
            statement.bind("workspace_id", workspaceId);

            return Flux.from(statement.execute());
        };
    }

}

package com.comet.opik.domain;

import com.comet.opik.utils.AsyncUtils.ContextAwareAction;
import com.comet.opik.utils.AsyncUtils.ContextAwareStream;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AsyncContextUtils {

    static ContextAwareStream<Result> bindWorkspaceIdToFlux(Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        };
    }

    static ContextAwareAction<Result> bindWorkspaceIdToMono(Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("workspace_id", workspaceId);
            return Mono.from(statement.execute());
        };
    }

    static ContextAwareAction<Result> bindUserNameAndWorkspaceContext(Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("user_name", userName);
            statement.bind("workspace_id", workspaceId);

            return Mono.from(statement.execute());
        };
    }

    static ContextAwareStream<Result> bindUserNameAndWorkspaceContextToStream(
            Statement statement) {
        return (userName, workspaceName, workspaceId) -> {
            statement.bind("user_name", userName);
            statement.bind("workspace_id", workspaceId);

            return Flux.from(statement.execute());
        };
    }

}

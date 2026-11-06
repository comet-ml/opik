package com.comet.opik.infrastructure.llm.decisions;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import reactor.core.publisher.Mono;

/**
 * One provider's Decisions API. Each provider's Guice module adds its client to the set that
 * {@link com.comet.opik.api.resources.v1.events.DecisionScoringService} picks from by the provider in
 * {@link DecisionModels}.
 *
 * <p>Implementations map {@link DecisionsRequest} and {@link DecisionsResponse} to their provider's format. A client
 * that calls a workspace-configured base URL sends through the guarded LLM transport. A failed call carries its
 * upstream status: 4xx as {@link jakarta.ws.rs.ClientErrorException}, 5xx as {@link jakarta.ws.rs.ServerErrorException},
 * so the online-scoring consumer drops permanent failures and redelivers transient ones.
 */
public interface DecisionsClient {

    LlmProvider provider();

    Mono<DecisionsResponse> decide(DecisionsRequest request, LlmProviderClientApiConfig config);
}

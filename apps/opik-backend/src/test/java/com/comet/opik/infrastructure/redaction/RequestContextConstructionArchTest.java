package com.comet.opik.infrastructure.redaction;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import reactor.util.context.Context;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural guard for the read-time masking decision.
 * <p>
 * Masking is decided once per request and carried on the reactive context, because row mapping runs long after
 * the resource method returned. Anything that builds that context by hand instead of through
 * {@code AsyncUtils.setRequestContext} copies a subset of the keys, and a key it does not know about is silently
 * dropped — which is how three separate streaming endpoints came to return stored content unmasked while every
 * unit test still passed. The failure is invisible at runtime: an absent decision looks exactly like a caller
 * who is permitted to see originals.
 * <p>
 * So the invariant is not "remember to add the key" but "there is one constructor of the request context".
 * Resources in the private and internal API must use it; event listeners and jobs are excluded because they have
 * no caller to decide against and their output feeds internal processing rather than a response.
 */
@AnalyzeClasses(packages = "com.comet.opik.api.resources", importOptions = ImportOption.DoNotIncludeTests.class)
class RequestContextConstructionArchTest {

    @ArchTest
    static final ArchRule the_reactive_request_context_has_a_single_constructor = noClasses()
            .that().resideInAnyPackage("..api.resources.v1.priv..", "..api.resources.v1.internal..")
            .should().callMethodWhere(DescribedPredicate.describe("Context.put, building the context by hand",
                    target -> target.getTargetOwner().isAssignableTo(Context.class)
                            && "put".equals(target.getName())))
            .because("""
                    a context assembled by hand drops keys it does not know about, and the read-time masking \
                    decision is one of them - which returns stored content to a caller who may not see it, with \
                    nothing failing. Use AsyncUtils.setRequestContext, snapshotting RequestContext on the request \
                    thread where the lambda runs at subscribe time
                    """)
            .allowEmptyShould(true);
}

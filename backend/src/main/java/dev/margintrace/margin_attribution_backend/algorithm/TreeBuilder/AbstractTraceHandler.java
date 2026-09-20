package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Executes one stage, then passes the same invocation context to the next stage. */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
abstract class AbstractTraceHandler {
    private final AbstractTraceHandler next;

    final void handle(TraceContext context) {
        execute(context);
        if (next != null) {
            next.handle(context);
        }
    }

    protected abstract void execute(TraceContext context);
}

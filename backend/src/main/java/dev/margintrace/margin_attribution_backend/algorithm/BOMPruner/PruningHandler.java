package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

/** Each handler processes the shared context, then passes it to the next handler. */
abstract class PruningHandler {
    private final PruningHandler next;

    PruningHandler(PruningHandler next) {
        this.next = next;
    }

    final void handle(PruningContext context) {
        process(context);
        if (next != null) {
            next.handle(context);
        }
    }

    protected abstract void process(PruningContext context);
}

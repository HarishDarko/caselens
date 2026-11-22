package com.harishdarko.caselens.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harishdarko.caselens.demo.DemoRetentionSweep;
import com.harishdarko.caselens.triage.OutboxRelay;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

public final class RelayHandler implements RequestStreamHandler {
    static final int BATCH_SIZE = 25;
    /** The sweep starts no new batch after this long; the function timeout is 30 seconds. */
    static final Duration SWEEP_BUDGET = Duration.ofSeconds(10);
    /**
     * Time held back after the sweep's last batch may start: one batch runs at most the 5 s statement timeout
     * (DemoWorkspaceCleanup.STATEMENT_TIMEOUT), and the response needs the rest.
     */
    static final Duration TIMEOUT_RESERVE = Duration.ofSeconds(8);

    private final ObjectMapper mapper;
    private final OutboxRelay relay;
    private final DemoRetentionSweep sweep;

    // Spring starts while Lambda constructs the handler, so the SnapStart snapshot of a published
    // version holds the started application. Starting it inside the first request took longer than
    // the 30-second timeout, so every scheduled sweep timed out before it could publish anything.
    public RelayHandler() {
        this(LambdaSpringContext::mapper, () -> LambdaSpringContext.get().getBean(OutboxRelay.class),
                () -> LambdaSpringContext.get().getBean(DemoRetentionSweep.class));
    }

    RelayHandler(Supplier<ObjectMapper> mapperSupplier, Supplier<OutboxRelay> relaySupplier,
            Supplier<DemoRetentionSweep> sweepSupplier) {
        this.mapper = mapperSupplier.get();
        this.relay = relaySupplier.get();
        this.sweep = sweepSupplier.get();
    }

    /**
     * Publishes one outbox batch, then purges expired workspaces and rate-limit buckets with whatever time is left.
     * The schedule already wakes Neon every 30 minutes for the outbox, so the purge adds no wake-ups of its own.
     */
    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        // Stop before the 30 s function timeout: 25 slow SQS calls would not fit.
        int published = relay.publishBatch(BATCH_SIZE,
                () -> context == null ? Long.MAX_VALUE : context.getRemainingTimeInMillis());
        // The budget is read after the outbox step, so a slow publish leaves the sweep less time or none.
        DemoRetentionSweep.Result purged = sweep.sweep(sweepBudget(context));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", published);
        result.put("purgedWorkspaces", purged.workspaces());
        result.put("purgedRateLimitBuckets", purged.rateLimitBuckets());
        result.put("status", "OK");
        mapper.writeValue(output, result);
    }

    static Duration sweepBudget(Context context) {
        if (context == null) return SWEEP_BUDGET;
        Duration remaining = Duration.ofMillis(context.getRemainingTimeInMillis()).minus(TIMEOUT_RESERVE);
        if (remaining.isNegative()) return Duration.ZERO;
        return remaining.compareTo(SWEEP_BUDGET) < 0 ? remaining : SWEEP_BUDGET;
    }
}

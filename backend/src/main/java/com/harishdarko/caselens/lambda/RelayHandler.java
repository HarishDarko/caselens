package com.harishdarko.caselens.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harishdarko.caselens.triage.OutboxRelay;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

public final class RelayHandler implements RequestStreamHandler {
    static final int BATCH_SIZE = 25;

    private final ObjectMapper mapper;
    private final OutboxRelay relay;

    // Spring starts while Lambda constructs the handler, so the SnapStart snapshot of a published
    // version holds the started application. Starting it inside the first request took longer than
    // the 30-second timeout, so every scheduled sweep timed out before it could publish anything.
    public RelayHandler() {
        this(LambdaSpringContext::mapper, () -> LambdaSpringContext.get().getBean(OutboxRelay.class));
    }

    RelayHandler(Supplier<ObjectMapper> mapperSupplier, Supplier<OutboxRelay> relaySupplier) {
        this.mapper = mapperSupplier.get();
        this.relay = relaySupplier.get();
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        int published = relay.publishBatch(BATCH_SIZE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", published);
        result.put("status", "OK");
        mapper.writeValue(output, result);
    }
}

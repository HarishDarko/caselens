package com.harishdarko.caselens.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.harishdarko.caselens.triage.OutboxRelay;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RelayHandler implements RequestStreamHandler {
    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        ObjectMapper mapper = LambdaSpringContext.mapper();
        int published = LambdaSpringContext.get().getBean(OutboxRelay.class).publishBatch(25);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", published);
        result.put("status", "OK");
        mapper.writeValue(output, result);
    }
}

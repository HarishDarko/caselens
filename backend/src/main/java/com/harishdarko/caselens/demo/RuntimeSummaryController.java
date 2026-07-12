package com.harishdarko.caselens.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo/runtime")
public class RuntimeSummaryController {
    private final RuntimeSummary summary;

    public RuntimeSummaryController(
            @Value("${caselens.runtime.environment:local-review}") String environment,
            @Value("${caselens.runtime.database:postgresql}") String database,
            @Value("${caselens.runtime.queue:localstack-sqs}") String queue,
            @Value("${caselens.runtime.ai-provider:deterministic}") String aiProvider) {
        this.summary = RuntimeSummary.from(environment, database, queue, aiProvider);
    }

    @GetMapping
    RuntimeSummary get() {
        return summary;
    }
}

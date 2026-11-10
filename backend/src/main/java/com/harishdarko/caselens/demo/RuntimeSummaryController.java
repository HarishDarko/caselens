package com.harishdarko.caselens.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo/runtime")
public class RuntimeSummaryController {
    private final RuntimeSummary summary;

    public RuntimeSummaryController(RuntimeSummary summary) {
        this.summary = summary;
    }

    @GetMapping
    RuntimeSummary get() {
        return summary;
    }
}

package com.harishdarko.caselens.triage;

import com.harishdarko.caselens.demo.DemoPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation")
class EvaluationController {
    private final EvaluationService service;

    EvaluationController(EvaluationService service) { this.service = service; }

    @GetMapping
    EvaluationSummary summary(@AuthenticationPrincipal DemoPrincipal principal) {
        return service.summarize(principal.workspaceId());
    }
}

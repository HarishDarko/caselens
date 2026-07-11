package com.harishdarko.caselens.triage;

import java.util.List;

public record PriorityResult(int score, List<RuleApplication> appliedRules) {
    public PriorityResult { appliedRules = List.copyOf(appliedRules); }
}

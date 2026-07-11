package com.harishdarko.caselens.triage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "evaluation_ground_truth")
public class EvaluationGroundTruth {
    @Id @Column(name = "scenario_key", length = 80) private String scenarioKey;
    @Enumerated(EnumType.STRING) @Column(name = "expected_category", nullable = false, length = 32) private Category expectedCategory;
    @Enumerated(EnumType.STRING) @Column(name = "expected_urgency", nullable = false, length = 16) private Urgency expectedUrgency;

    protected EvaluationGroundTruth() {}

    public EvaluationGroundTruth(String scenarioKey, Category expectedCategory, Urgency expectedUrgency) {
        this.scenarioKey = scenarioKey;
        this.expectedCategory = expectedCategory;
        this.expectedUrgency = expectedUrgency;
    }

    public String getScenarioKey() { return scenarioKey; }
    public Category getExpectedCategory() { return expectedCategory; }
    public Urgency getExpectedUrgency() { return expectedUrgency; }
}

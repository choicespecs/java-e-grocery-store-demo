package com.demo.grocery.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Accumulates a step-by-step record of one checkout saga execution.
 * Passed through CheckoutService so each stage can log its outcome.
 * The controller adds the completed trace to the model so the UI can
 * show exactly which steps ran, which failed, and which compensations fired.
 */
public class SagaTrace {

    public enum Status { OK, FAILED, COMPENSATED, SKIPPED, DEGRADED }

    public record Step(String number, String operation, Status status, String detail) {}

    private final List<Step> steps = new ArrayList<>();

    public void ok(String number, String operation) {
        steps.add(new Step(number, operation, Status.OK, null));
    }

    public void failed(String number, String operation, String detail) {
        steps.add(new Step(number, operation, Status.FAILED, detail));
    }

    public void compensated(String operation, String detail) {
        steps.add(new Step("↩", operation, Status.COMPENSATED, detail));
    }

    public void skipped(String number, String operation, String detail) {
        steps.add(new Step(number, operation, Status.SKIPPED, detail));
    }

    /** Degraded = step ran but at reduced functionality (e.g. graceful degradation). */
    public void degraded(String number, String operation, String detail) {
        steps.add(new Step(number, operation, Status.DEGRADED, detail));
    }

    public List<Step> getSteps() { return Collections.unmodifiableList(steps); }
    public boolean hasSteps() { return !steps.isEmpty(); }
}

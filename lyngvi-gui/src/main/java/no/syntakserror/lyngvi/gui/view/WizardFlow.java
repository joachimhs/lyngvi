package no.syntakserror.lyngvi.gui.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The step sequence behind {@link SandboxWizard}: where it is, what each step has achieved, and what the one
 * forward button may therefore do — run the step's own work, or leave for the next step. Pure, with no toolkit,
 * so the gating is unit-tested rather than clicked through.
 */
public final class WizardFlow {

    /** What a step has achieved; a step whose work has not run yet is {@link #PENDING}. */
    public enum State { PENDING, RUNNING, DONE, FAILED }

    /** What the forward button does when it is pressed. */
    public enum Forward { RUN, ADVANCE }

    /**
     * One step.
     *
     * @param key      what its texts are under in the bundle ({@code gui.wizard.<key>.title} and {@code .text})
     * @param optional true when the wizard may be left behind without it, so a failure here is not a dead end
     */
    public record Step(String key, boolean optional) {}

    private final List<Step> steps;
    private final List<State> states;
    private int index;

    public WizardFlow(List<Step> steps) {
        if (steps.isEmpty()) throw new IllegalArgumentException("a wizard needs at least one step");
        this.steps = List.copyOf(steps);
        this.states = new ArrayList<>(Collections.nCopies(steps.size(), State.PENDING));
    }

    public List<Step> steps() {
        return steps;
    }

    public int index() {
        return index;
    }

    public Step current() {
        return steps.get(index);
    }

    public State state() {
        return states.get(index);
    }

    public State state(int step) {
        return states.get(step);
    }

    /** Records what the current step's work ended in. Only the current step can be working, so it needs no index. */
    public void setState(State state) {
        states.set(index, state);
    }

    public boolean last() {
        return index == steps.size() - 1;
    }

    /** Going back while the step is working would leave the work without a page to report to, so it is not offered. */
    public boolean canBack() {
        return index > 0 && state() != State.RUNNING;
    }

    public Forward forward() {
        if (state() == State.DONE) return Forward.ADVANCE;
        if (state() == State.RUNNING) return Forward.RUN;
        return current().optional() ? Forward.ADVANCE : Forward.RUN;
    }

    /** {@code ready} is the step's own input check — that a folder has been chosen, say; work needs it, leaving does not. */
    public boolean canForward(boolean ready) {
        return state() != State.RUNNING && (forward() == Forward.ADVANCE || ready);
    }

    /**
     * What the forward button reads: the step's own action while it still has work to do ({@code actionKey}, or
     * «Next» when the step has none), «Try again» after a failure, and «Next» or «Finish» once it may be left.
     */
    public String forwardKey(String actionKey) {
        if (forward() == Forward.ADVANCE) return last() ? "gui.wizard.finish" : "gui.wizard.next";
        if (state() == State.FAILED) return "gui.wizard.retry";
        return actionKey == null ? "gui.wizard.next" : actionKey;
    }

    /** The mark in front of a step in the rail: done, failed, or the step's number. */
    public String marker(int step) {
        return switch (states.get(step)) {
            case DONE -> "✓";
            case FAILED -> "!";
            case PENDING, RUNNING -> String.valueOf(step + 1);
        };
    }

    public void advance() {
        if (!last()) index++;
    }

    public void back() {
        if (index > 0) index--;
    }
}

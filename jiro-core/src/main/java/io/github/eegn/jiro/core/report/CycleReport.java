package io.github.eegn.jiro.core.report;

import java.util.List;

/**
 * The verdict of one dev-mode cycle, in a form another program can act on.
 *
 * <p>This exists for coding agents. An agent working in a repo where jiro is already running should
 * never invoke a build: it edits a file, and a moment later reads the answer. The two fields that
 * make that safe are {@link #cycleId()} and {@link #inputWatermarkMillis()} — without them an agent
 * that reads immediately after writing gets the previous cycle's verdict and concludes its edit
 * compiled when it has not been looked at yet.
 *
 * <p>The contract for a reader is: wait until {@code state} is a terminal one <em>and</em>
 * {@code inputWatermarkMillis} is at or past the modification time of the file you just wrote.
 *
 * @param cycleId              monotonically increasing; a new value means a fresh verdict
 * @param state                see {@link State}
 * @param inputWatermarkMillis newest last-modified time among the sources this cycle considered
 * @param startedAtMillis      wall-clock start of the cycle
 * @param durationMillis       compile plus test time, once the cycle is terminal
 * @param compileErrors        formatted javac errors; non-empty implies {@link State#COMPILE_ERROR}
 * @param selectionReason      why these tests and not others, in one human-readable line
 * @param selectedTests        how many tests the selector chose
 * @param passed               how many of them passed
 * @param failures             the ones that did not, with a one-line message each
 */
public record CycleReport(long cycleId,
                          State state,
                          long inputWatermarkMillis,
                          long startedAtMillis,
                          long durationMillis,
                          List<String> compileErrors,
                          String selectionReason,
                          int selectedTests,
                          int passed,
                          List<FailedTest> failures) {

    public enum State {
        /** Sources changed; javac is running. Not a verdict. */
        COMPILING,
        /** Compilation succeeded; selected tests are executing. Not a verdict. */
        RUNNING,
        /** Terminal. Compilation failed; no tests ran. */
        COMPILE_ERROR,
        /** Terminal. Everything selected passed. */
        GREEN,
        /** Terminal. At least one selected test failed. */
        RED;

        /** Whether this state carries a verdict a reader can trust. */
        public boolean isTerminal() {
            return this == COMPILE_ERROR || this == GREEN || this == RED;
        }
    }

    public record FailedTest(String uniqueId, String displayName, String message) {
    }

    public static CycleReport starting(long cycleId, long inputWatermarkMillis) {
        return new CycleReport(cycleId, State.COMPILING, inputWatermarkMillis,
                System.currentTimeMillis(), 0L, List.of(), "", 0, 0, List.of());
    }

    public CycleReport running(String selectionReason, int selectedTests) {
        return new CycleReport(cycleId, State.RUNNING, inputWatermarkMillis, startedAtMillis,
                0L, List.of(), selectionReason, selectedTests, 0, List.of());
    }

    public CycleReport compileFailed(List<String> errors) {
        return new CycleReport(cycleId, State.COMPILE_ERROR, inputWatermarkMillis, startedAtMillis,
                System.currentTimeMillis() - startedAtMillis, errors,
                "compilation failed, no tests selected", 0, 0, List.of());
    }

    public CycleReport finished(String selectionReason, int selected, int passed,
                                List<FailedTest> failures) {
        return new CycleReport(cycleId, failures.isEmpty() ? State.GREEN : State.RED,
                inputWatermarkMillis, startedAtMillis, System.currentTimeMillis() - startedAtMillis,
                List.of(), selectionReason, selected, passed, failures);
    }
}

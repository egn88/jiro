package io.github.eegn.jiro.runner;

import io.github.eegn.jiro.runtime.CoverageRecorder;
import io.github.eegn.jiro.runtime.MethodRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attributes recorded probes to the test that caused them.
 *
 * <p>Implemented as an {@link InvocationHandler} rather than by implementing
 * {@code TestExecutionListener} directly, because that interface is loaded from the project's own
 * JUnit and this class is not. A dynamic proxy is the only way to hand the launcher a listener it
 * will accept without the runner binding to a JUnit version at compile time.
 *
 * <p>JUnit fires {@code executionStarted} for a test before its {@code @BeforeEach} callbacks, so
 * per-test setup lands in the right bucket for free. {@code @BeforeAll} does not: it runs inside the
 * class container, before any test has started. That window is captured separately and unioned into
 * every test of the class, which over-attributes slightly — and over-attribution is the safe
 * direction, since it can only cause a test to run when it did not need to.
 *
 * <p>Containers nest, so the captured prefix is a stack rather than a field. A {@code @Nested} class
 * runs its own {@code @BeforeAll} inside the outer class's container, and its tests need both
 * windows; when the inner container finishes, the outer one's prefix has to come back unchanged.
 * Keeping this exact also keeps {@code CoverageRecorder}'s overlap detection honest — every
 * {@code beginTest} below is preceded by an {@code endTest}, so a session found already open really
 * does mean two tests are running at once.
 */
final class CoverageBindingListener implements InvocationHandler {

    private final JUnitBridge junit;
    private final Map<String, TestOutcome> outcomes = new LinkedHashMap<>();

    /** Class containers currently open, innermost first, each remembering the prefix it inherited. */
    private final Deque<Frame> containers = new ArrayDeque<>();

    /** Coverage from the {@code @BeforeAll} windows of every container currently open. */
    private final Set<String> prefix = new LinkedHashSet<>();

    private boolean recording;
    private String[] cachedKeys = new String[0];

    CoverageBindingListener(JUnitBridge junit) {
        this.junit = junit;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        switch (method.getName()) {
            case "executionStarted" -> executionStarted(arguments[0]);
            case "executionFinished" -> executionFinished(arguments[0], arguments[1]);
            case "toString" -> {
                return "jiro-coverage-listener";
            }
            case "hashCode" -> {
                return System.identityHashCode(proxy);
            }
            case "equals" -> {
                return proxy == arguments[0];
            }
            default -> {
                // executionSkipped, testPlanExecutionStarted, dynamicTestRegistered,
                // reportingEntryPublished, and whatever a future platform adds. A skipped test
                // records nothing and must keep the coverage it already had, so leaving it out of
                // the outcomes is exactly right: the index keeps its previous entry.
            }
        }
        return null;
    }

    private void executionStarted(Object identifier) throws ReflectiveOperationException {
        if (junit.isTest(identifier)) {
            closeIntoPrefix();
            CoverageRecorder.beginTest();
            recording = true;
            return;
        }
        if (junit.isClassSource(identifier)) {
            closeIntoPrefix();
            containers.push(new Frame(junit.uniqueId(identifier), Set.copyOf(prefix)));
            CoverageRecorder.beginTest();
            recording = true;
        }
    }

    private void executionFinished(Object identifier, Object result) throws ReflectiveOperationException {
        if (junit.isTest(identifier)) {
            Set<String> covered = new LinkedHashSet<>(stopRecording());
            covered.addAll(prefix);
            outcomes.put(junit.uniqueId(identifier), new TestOutcome(
                    junit.uniqueId(identifier),
                    junit.displayName(identifier),
                    junit.status(result),
                    junit.failureDetail(result),
                    covered));
            return;
        }
        if (junit.isClassSource(identifier) && !containers.isEmpty()
                && containers.peek().uniqueId().equals(junit.uniqueId(identifier))) {
            stopRecording();
            // Restore whatever the enclosing container had, discarding this one's @BeforeAll window.
            Frame frame = containers.pop();
            prefix.clear();
            prefix.addAll(frame.inheritedPrefix());
        }
    }

    /** Folds an open container session into the accumulated prefix. */
    private void closeIntoPrefix() {
        if (recording) {
            prefix.addAll(keysOf(CoverageRecorder.endTest()));
            recording = false;
        }
    }

    private Set<String> stopRecording() {
        if (!recording) {
            return Set.of();
        }
        recording = false;
        return keysOf(CoverageRecorder.endTest());
    }

    private record Frame(String uniqueId, Set<String> inheritedPrefix) {
    }

    List<TestOutcome> outcomes() {
        return new ArrayList<>(outcomes.values());
    }

    private Set<String> keysOf(int[] methodIds) {
        if (methodIds.length == 0) {
            return Set.of();
        }
        // The registry only ever grows, and re-snapshotting it per test would dominate the cost of
        // a short test, so it is refreshed only when new classes have been instrumented.
        if (cachedKeys.length != MethodRegistry.size()) {
            cachedKeys = MethodRegistry.snapshot();
        }
        Set<String> keys = new LinkedHashSet<>(methodIds.length);
        for (int methodId : methodIds) {
            if (methodId < cachedKeys.length) {
                keys.add(cachedKeys[methodId]);
            }
        }
        return keys;
    }

    /** @param failure {@code [type, message, frame...]}, or {@code null} when the test passed */
    record TestOutcome(String uniqueId, String displayName, String status, List<String> failure,
                       Set<String> coveredMethods) {
    }
}

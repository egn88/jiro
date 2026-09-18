package io.github.eegn.jiro.runtime;

/**
 * Collects, for exactly one running test at a time, the set of application methods that
 * were entered. Instrumented methods call {@link #hit(int)} as their first instruction.
 *
 * <p>This class is appended to the bootstrap classpath by the agent so that application
 * classes can reach it no matter which classloader defined them. It must therefore never
 * reference anything outside {@code java.base}.
 *
 * <p>Probes are held in a {@code boolean[]}: writes to distinct elements of a boolean array
 * are atomic and the only write performed is {@code true}, so hits recorded from threads the
 * test spawned are picked up without synchronisation. What this does <em>not</em> tolerate is
 * two tests running at once, because there is a single active session.
 *
 * <p>Rather than trying to predict that from configuration — which would miss {@code @Execution}
 * annotations and anything a future JUnit version invents — overlap is detected empirically: a
 * second {@link #beginTest()} arriving while a session is already open can only mean two tests are
 * in flight, and {@link #overlapDetected()} latches. Mis-attributed coverage is silent and produces
 * wrong selection later, so jiro treats this as fatal rather than degrading quietly.
 */
public final class CoverageRecorder {

    /** Probe array for the test currently executing, or {@code null} between tests. */
    private static volatile Session active;

    /** High-water mark of registered method ids, used to size the next session. */
    private static volatile int capacity = 8192;

    /** Latches if two tests were ever recording at once. Never reset within a run. */
    private static volatile boolean overlap;

    private CoverageRecorder() {
    }

    /** Called as the first instruction of every instrumented method body. */
    public static void hit(int methodId) {
        Session session = active;
        if (session == null) {
            return;
        }
        boolean[] probes = session.probes;
        if (methodId < probes.length) {
            probes[methodId] = true;
        }
    }

    /**
     * Widens future sessions, and the running one, to hold {@code required} probes. The agent
     * calls this while transforming a class, which always happens before any method of that
     * class can be entered.
     */
    public static void ensureCapacity(int required) {
        if (required > capacity) {
            capacity = required;
        }
        Session session = active;
        if (session != null) {
            session.grow(required);
        }
    }

    /**
     * Starts recording.
     *
     * <p>A session already being open means two tests overlapped, which no correct listener
     * sequence produces. The condition is latched rather than thrown, so the run completes and the
     * caller can report it once instead of failing a random test.
     */
    public static void beginTest() {
        if (active != null) {
            overlap = true;
        }
        active = new Session(capacity);
    }

    /** Whether two tests were ever recording simultaneously, making coverage untrustworthy. */
    public static boolean overlapDetected() {
        return overlap;
    }

    /**
     * Stops recording and returns the ids of every method entered since {@link #beginTest()},
     * in ascending order. Returns an empty array if no session was open.
     */
    public static int[] endTest() {
        Session session = active;
        active = null;
        if (session == null) {
            return new int[0];
        }
        boolean[] probes = session.probes;
        int count = 0;
        for (boolean probe : probes) {
            if (probe) {
                count++;
            }
        }
        int[] hits = new int[count];
        int next = 0;
        for (int id = 0; id < probes.length; id++) {
            if (probes[id]) {
                hits[next++] = id;
            }
        }
        return hits;
    }

    private static final class Session {
        /** Volatile so that a grow performed by a class-loading thread is seen by the rest. */
        volatile boolean[] probes;

        Session(int capacity) {
            this.probes = new boolean[capacity];
        }

        synchronized void grow(int required) {
            boolean[] current = probes;
            if (current.length >= required) {
                return;
            }
            boolean[] widened = new boolean[Math.max(required, current.length * 2)];
            System.arraycopy(current, 0, widened, 0, current.length);
            probes = widened;
        }
    }
}

package io.github.eegn.jiro.runner;

/**
 * The line protocol between the Maven process and the forked test JVM.
 *
 * <p>Deliberately plain text: when a dev-mode loop misbehaves, being able to read the conversation
 * in a log is worth more than the bytes a binary framing would save.
 *
 * <pre>
 *   maven -> runner   DISCOVER
 *   runner -> maven   TEST &lt;uniqueId&gt; ...  DONE
 *
 *   maven -> runner   RUN &lt;count&gt;      (then &lt;count&gt; unique ids, one per line)
 *   maven -> runner   RUNALL
 *   runner -> maven   RESULT &lt;PASSED|FAILED|ABORTED&gt; &lt;millis&gt; &lt;uniqueId&gt;
 *                     FAILURE &lt;uniqueId&gt; &lt;single-line message&gt;
 *                     COV &lt;uniqueId&gt; &lt;methodKey&gt;,&lt;methodKey&gt;...
 *                     DONE
 *
 *   maven -> runner   SHUTDOWN
 * </pre>
 */
public final class Protocol {

    public static final String DISCOVER = "DISCOVER";
    public static final String RUN = "RUN";
    public static final String RUNALL = "RUNALL";
    public static final String SHUTDOWN = "SHUTDOWN";

    public static final String TEST = "TEST";
    public static final String RESULT = "RESULT";
    public static final String FAILURE = "FAILURE";
    public static final String COVERAGE = "COV";
    public static final String DONE = "DONE";

    /**
     * Emitted in place of a run's results when two tests were found recording at once. Coverage
     * from such a run cannot be attributed, so the caller must stop rather than index it.
     */
    public static final String PARALLEL = "<parallel>";

    private Protocol() {
    }

    /** Collapses a throwable message to one line so it cannot desynchronise the stream. */
    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }
}

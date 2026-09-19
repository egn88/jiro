package io.github.eegn.jiro.runner;

import java.util.ArrayList;
import java.util.List;

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

    // (java.util imports kept local to avoid widening this class's surface)

    public static final String DISCOVER = "DISCOVER";
    public static final String RUN = "RUN";
    public static final String RUNALL = "RUNALL";
    public static final String SHUTDOWN = "SHUTDOWN";

    public static final String TEST = "TEST";
    public static final String RESULT = "RESULT";
    public static final String FAILURE = "FAILURE";
    public static final String COVERAGE = "COV";
    public static final String DONE = "DONE";
    public static final String NAME = "NAME";

    /**
     * Emitted in place of a run's results when two tests were found recording at once. Coverage
     * from such a run cannot be attributed, so the caller must stop rather than index it.
     */
    public static final String PARALLEL = "<parallel>";

    /**
     * Separates the fields of a structured payload. U+0001 is used because the protocol is
     * line-based and the payload carries exception messages, which routinely contain tabs, commas,
     * colons and quotes but never a control character.
     */
    private static final char UNIT = '\u0001';

    private Protocol() {
    }

    /** Collapses text to one line so it cannot desynchronise the stream. */
    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * Packs fields into one line, escaping anything that would break the framing. Newlines survive
     * as {@code \n} rather than being flattened to spaces, so a stack trace arrives intact.
     */
    public static String pack(List<String> fields) {
        StringBuilder packed = new StringBuilder();
        for (String field : fields) {
            if (packed.length() > 0) {
                packed.append(UNIT);
            }
            packed.append(escape(field));
        }
        return packed.toString();
    }

    public static List<String> unpack(String payload) {
        List<String> fields = new ArrayList<>();
        for (String field : payload.split(String.valueOf(UNIT), -1)) {
            fields.add(unescape(field));
        }
        return fields;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace(String.valueOf(UNIT), " ");
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == 'n' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

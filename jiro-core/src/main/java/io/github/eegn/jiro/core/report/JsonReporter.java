package io.github.eegn.jiro.core.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Publishes {@link CycleReport}s to two files under the jiro state directory:
 *
 * <ul>
 *   <li>{@code status.json} &mdash; the current cycle, replaced atomically</li>
 *   <li>{@code events.ndjson} &mdash; one line per cycle, appended, for anything that wants history</li>
 * </ul>
 *
 * <p>Files rather than a socket or an RPC endpoint, because the intended reader is a coding agent
 * with shell access: {@code cat target/jiro/status.json} needs no client library, no port and no
 * handshake, and works identically from a hook, a script or a person.
 *
 * <p>{@code status.json} is written to a temporary file and moved into place, so a reader polling
 * in a tight loop can never observe a half-written document.
 *
 * <p>JSON is emitted by hand: jiro-core is on the plugin's classpath inside Maven, and adding a
 * serialisation library there buys nothing for the handful of fields involved.
 */
public final class JsonReporter {

    private final Path statusFile;
    private final Path eventsFile;

    public JsonReporter(Path stateDirectory) {
        this.statusFile = stateDirectory.resolve("status.json");
        this.eventsFile = stateDirectory.resolve("events.ndjson");
    }

    public Path statusFile() {
        return statusFile;
    }

    public void publish(CycleReport report) throws IOException {
        Files.createDirectories(statusFile.getParent());
        String json = toJson(report, true);

        Path temporary = statusFile.resolveSibling("status.json.tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, statusFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
            Files.move(temporary, statusFile, StandardCopyOption.REPLACE_EXISTING);
        }

        if (report.state().isTerminal()) {
            // Compact, because the contract of .ndjson is one object per line. Appending the
            // indented form produced a file that no line-oriented reader could parse.
            Files.writeString(eventsFile, toJson(report, false) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /**
     * @param pretty indented and multi-line for {@code status.json}, which a person reads; compact
     *               for {@code events.ndjson}, where one object must occupy exactly one line
     */
    private static String toJson(CycleReport report, boolean pretty) {
        String newline = pretty ? "\n" : "";
        String indent = pretty ? "  " : "";
        String space = pretty ? " " : "";

        StringBuilder json = new StringBuilder(512);
        json.append('{').append(newline);
        appendField(json, indent, space, newline, "cycleId", String.valueOf(report.cycleId()));
        appendField(json, indent, space, newline, "state", quote(report.state().name()));
        appendField(json, indent, space, newline, "terminal", String.valueOf(report.state().isTerminal()));
        appendField(json, indent, space, newline, "inputWatermarkMillis",
                String.valueOf(report.inputWatermarkMillis()));
        appendField(json, indent, space, newline, "startedAtMillis",
                String.valueOf(report.startedAtMillis()));
        appendField(json, indent, space, newline, "durationMillis",
                String.valueOf(report.durationMillis()));
        appendField(json, indent, space, newline, "selectionReason", quote(report.selectionReason()));
        appendField(json, indent, space, newline, "selectedTests", String.valueOf(report.selectedTests()));
        appendField(json, indent, space, newline, "passed", String.valueOf(report.passed()));
        appendField(json, indent, space, newline, "failed", String.valueOf(report.failures().size()));
        appendField(json, indent, space, newline, "compileErrors", stringArray(report.compileErrors()));

        json.append(indent).append("\"failures\":").append(space).append('[');
        List<CycleReport.FailedTest> failures = report.failures();
        for (int i = 0; i < failures.size(); i++) {
            CycleReport.FailedTest failure = failures.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append(newline).append(indent).append(indent)
                    .append("{\"uniqueId\":").append(space).append(quote(failure.uniqueId()))
                    .append(",").append(space).append("\"displayName\":").append(space)
                    .append(quote(failure.displayName()))
                    .append(",").append(space).append("\"message\":").append(space)
                    .append(quote(failure.message())).append('}');
        }
        if (!failures.isEmpty()) {
            json.append(newline).append(indent);
        }
        json.append(']').append(newline);
        json.append('}');
        return json.toString();
    }

    private static void appendField(StringBuilder json, String indent, String space,
                                    String newline, String name, String renderedValue) {
        json.append(indent).append('"').append(name).append("\":").append(space)
                .append(renderedValue).append(',').append(newline);
    }

    private static String stringArray(List<String> values) {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                array.append(", ");
            }
            array.append(quote(values.get(i)));
        }
        return array.append(']').toString();
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}

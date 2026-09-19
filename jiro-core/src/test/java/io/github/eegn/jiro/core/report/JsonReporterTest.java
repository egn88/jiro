package io.github.eegn.jiro.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eegn.jiro.core.compile.CompileError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonReporterTest {

    @TempDir
    Path state;

    @Test
    void everyEventOccupiesExactlyOneLine() throws IOException {
        JsonReporter reporter = new JsonReporter(state);

        reporter.publish(CycleReport.starting(1, 100L).finished("first", 2, 2, List.of()));
        reporter.publish(CycleReport.starting(2, 200L).finished("second", 1, 0, List.of(
                new CycleReport.FailedTest("id", "Some.test", "java.lang.AssertionError",
                        "boom\nwith a newline", List.of("a.B.c(B.java:12)")))));

        List<String> lines = Files.readAllLines(state.resolve("events.ndjson"));
        assertEquals(2, lines.size(), "ndjson means one object per line");
        for (String line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"), "not one object: " + line);
        }
        assertTrue(lines.get(1).contains("\\n"), "embedded newlines must be escaped, not literal");
    }

    @Test
    void nonTerminalStatesAreNotAppendedToTheEventLog() throws IOException {
        JsonReporter reporter = new JsonReporter(state);
        reporter.publish(CycleReport.starting(1, 100L));

        assertTrue(Files.exists(reporter.statusFile()));
        assertTrue(Files.notExists(state.resolve("events.ndjson")),
                "a cycle still running is not yet a verdict");
    }

    @Test
    void statusCarriesTheWatermarkAndTerminalFlagAReaderNeeds() throws IOException {
        JsonReporter reporter = new JsonReporter(state);
        reporter.publish(CycleReport.starting(7, 12345L).finished("reason", 1, 1, List.of()));

        String status = Files.readString(reporter.statusFile());
        assertTrue(status.contains("\"cycleId\": 7"));
        assertTrue(status.contains("\"inputWatermarkMillis\": 12345"));
        assertTrue(status.contains("\"terminal\": true"));
        assertTrue(status.contains("\"state\": \"GREEN\""));
    }

    @Test
    void compileFailureIsTerminalAndCarriesTheErrors() throws IOException {
        JsonReporter reporter = new JsonReporter(state);
        reporter.publish(CycleReport.starting(3, 1L).compileFailed(List.of(
                new CompileError("/src/Foo.java", 9, 4, "cannot find symbol", "int x = y;"))));

        String status = Files.readString(reporter.statusFile());
        assertTrue(status.contains("\"state\": \"COMPILE_ERROR\""));
        assertTrue(status.contains("\"file\": \"/src/Foo.java\""), status);
        assertTrue(status.contains("\"line\": 9"), status);
        assertTrue(status.contains("\"column\": 4"), status);
        assertTrue(status.contains("cannot find symbol"));
        assertTrue(status.contains("int x = y;"),
                "the offending source line saves the reader opening the file");
    }

    @Test
    void failuresCarryTypeMessageAndProjectFrames() throws IOException {
        JsonReporter reporter = new JsonReporter(state);
        reporter.publish(CycleReport.starting(4, 1L).finished("r", 1, 0, List.of(
                new CycleReport.FailedTest("uid", "OrderTest.total",
                        "org.opentest4j.AssertionFailedError", "expected: <90> but was: <100>",
                        List.of("com.acme.OrderTest.total(OrderTest.java:41)")))));

        String status = Files.readString(reporter.statusFile());
        assertTrue(status.contains("\"type\": \"org.opentest4j.AssertionFailedError\""), status);
        assertTrue(status.contains("expected: <90> but was: <100>"), status);
        assertTrue(status.contains("OrderTest.java:41"),
                "the frame is how an agent finds the line to edit");
    }
}

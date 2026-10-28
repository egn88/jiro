package io.github.eegn.jiro.core.watch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceWatcherTest {

    @TempDir
    Path root;

    /**
     * A package move creates the directory and writes its files before the watcher has had any
     * chance to register the new directory, so no event ever arrives for those files. They must be
     * reported anyway, or the moved classes are never compiled.
     */
    @Test
    void reportsFilesWrittenIntoANewDirectoryBeforeItWasWatched() throws Exception {
        try (SourceWatcher watcher = new SourceWatcher(List.of(root))) {
            Path moved = Files.createDirectories(root.resolve("com/acme/renamed"));
            Path source = Files.writeString(moved.resolve("Diner.java"), "class Diner {}");

            List<FileChange> changes = watcher.awaitChanges(Duration.ofMillis(300));

            assertTrue(changes.stream().anyMatch(change -> change.path().equals(source)), changes.toString());
        }
    }
}

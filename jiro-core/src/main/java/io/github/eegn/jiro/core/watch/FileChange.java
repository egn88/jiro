package io.github.eegn.jiro.core.watch;

import java.nio.file.Path;

/** A single observed change to a watched source file. */
public record FileChange(Path path, Kind kind) {

    public enum Kind {
        CREATED,
        MODIFIED,
        DELETED
    }

    public boolean isJavaSource() {
        return path.getFileName().toString().endsWith(".java");
    }
}

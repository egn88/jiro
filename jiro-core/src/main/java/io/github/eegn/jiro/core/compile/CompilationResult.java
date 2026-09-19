package io.github.eegn.jiro.core.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Outcome of one incremental compilation.
 *
 * @param successful whether bytecode was produced for every requested source
 * @param errors     structured compiler errors, in the order javac reported them
 * @param requested  the sources handed to javac
 */
public record CompilationResult(boolean successful, List<CompileError> errors, Set<Path> requested) {

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}

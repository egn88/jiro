package io.github.eegn.jiro.core.compile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles just the sources that changed, in-process, against the already-built output directory.
 *
 * <p>Using the JSR-199 compiler rather than forking {@code javac} is most of where the speed comes
 * from: the compiler stays warm across cycles, so a one-file edit compiles in tens of milliseconds
 * instead of the second or so a fresh JVM costs.
 *
 * <p>Annotation processing is left enabled. Projects that rely on Lombok or MapStruct would
 * otherwise fail to compile a single file in isolation.
 */
public final class IncrementalCompiler {

    private final List<Path> classpath;
    private final Path outputDirectory;
    private final List<String> extraOptions;
    private final JavaCompiler compiler;

    public IncrementalCompiler(List<Path> classpath, Path outputDirectory, List<String> extraOptions) {
        this.classpath = List.copyOf(classpath);
        this.outputDirectory = outputDirectory;
        this.extraOptions = List.copyOf(extraOptions);
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No JDK compiler available. jiro must run on a JDK, not a JRE.");
        }
    }

    public CompilationResult compile(Collection<Path> sources) {
        Set<Path> existing = new LinkedHashSet<>();
        for (Path source : sources) {
            if (Files.isRegularFile(source)) {
                existing.add(source);
            }
        }
        if (existing.isEmpty()) {
            return new CompilationResult(true, List.of(), Set.of());
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {

            Files.createDirectories(outputDirectory);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_PATH, toFiles(classpath));

            List<String> options = new ArrayList<>(List.of("-g", "-nowarn"));
            options.addAll(extraOptions);

            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(existing);
            boolean successful = compiler.getTask(null, fileManager, collector, options, null, units)
                    .call();
            return new CompilationResult(successful, toErrors(collector), existing);
        } catch (IOException failure) {
            return new CompilationResult(false,
                    List.of(new CompileError(null, 0, 0, String.valueOf(failure), null)), existing);
        }
    }

    private static List<File> toFiles(List<Path> paths) {
        List<File> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            files.add(path.toFile());
        }
        return files;
    }

    private static List<CompileError> toErrors(DiagnosticCollector<JavaFileObject> collector) {
        List<CompileError> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            JavaFileObject source = diagnostic.getSource();
            String file = source == null ? null : Path.of(source.getName()).toAbsolutePath().toString();
            errors.add(new CompileError(
                    file,
                    diagnostic.getLineNumber() < 0 ? 0 : diagnostic.getLineNumber(),
                    diagnostic.getColumnNumber() < 0 ? 0 : diagnostic.getColumnNumber(),
                    diagnostic.getMessage(Locale.ROOT),
                    readLine(file, diagnostic.getLineNumber())));
        }
        return errors;
    }

    /** The offending source line, so a reader does not have to open the file to see it. */
    private static String readLine(String file, long line) {
        if (file == null || line <= 0) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(file));
            return line <= lines.size() ? lines.get((int) line - 1).strip() : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}

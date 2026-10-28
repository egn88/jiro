package io.github.eegn.jiro.rewrite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.Validated;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.style.Autodetect;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markup;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.tree.ParseError;
import org.openrewrite.tree.ParsingExecutionContextView;

/**
 * Runs OpenRewrite recipes against a project whose sources stay parsed between requests.
 *
 * <p>This is the only class the plugin touches, and it touches it only through
 * {@code Function<Map<String, Object>, Map<String, Object>>}: the plugin and this class live in
 * different classloaders and share nothing but {@code java.base}. Requests and responses are
 * therefore plain maps of strings, numbers, booleans and lists.
 *
 * <p>Configuration, passed to the constructor:
 * <ul>
 *   <li>{@code baseDir} ({@link Path}) &mdash; the module directory; result paths are relative to it</li>
 *   <li>{@code mainSourceRoots}, {@code testSourceRoots} ({@code List<Path>})</li>
 *   <li>{@code compileClasspath}, {@code testClasspath} ({@code List<Path>}) &mdash; the test
 *       classpath must include the main output directory, which jiro keeps fresh</li>
 *   <li>{@code charset} ({@link String}, optional) &mdash; {@code project.build.sourceEncoding}</li>
 *   <li>{@code lock} ({@link Lock}, optional) &mdash; held while parsing, so a parse never reads a
 *       class file the incremental compiler is halfway through writing</li>
 *   <li>{@code log} ({@code Consumer<String>}, optional)</li>
 * </ul>
 *
 * <p>Requests: {@code {op: "warm"}} parses everything and returns; {@code {op: "run",
 * recipeYaml: String, recipeName: String|null}} runs a declarative recipe and writes the changed
 * files. The response always has {@code ok}; see {@link #apply} for the rest.
 */
public final class RewriteService implements Function<Map<String, Object>, Map<String, Object>> {

    private final Path baseDir;
    private final List<Path> mainRoots;
    private final List<Path> testRoots;
    private final List<Path> compileClasspath;
    private final List<Path> testClasspath;
    private final Charset charset;
    private final Lock lock;
    private final Consumer<String> log;

    private final LstCache cache = new LstCache();
    private Map<String, JavaSourceSet> sourceSets;
    private Autodetect style;
    private boolean styleStale = true;

    @SuppressWarnings("unchecked")
    public RewriteService(Map<String, Object> config) {
        this.baseDir = ((Path) config.get("baseDir")).toAbsolutePath().normalize();
        this.mainRoots = absolute((List<Path>) config.getOrDefault("mainSourceRoots", List.of()));
        this.testRoots = absolute((List<Path>) config.getOrDefault("testSourceRoots", List.of()));
        this.compileClasspath = (List<Path>) config.getOrDefault("compileClasspath", List.of());
        this.testClasspath = (List<Path>) config.getOrDefault("testClasspath", List.of());
        Object encoding = config.get("charset");
        this.charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName((String) encoding);
        Object suppliedLock = config.get("lock");
        this.lock = suppliedLock == null ? new ReentrantLock() : (Lock) suppliedLock;
        Object suppliedLog = config.get("log");
        this.log = suppliedLog == null ? message -> { } : (Consumer<String>) suppliedLog;
    }

    /**
     * Handles one request. Never throws: failures come back as {@code ok=false} with {@code error}.
     *
     * <p>Response keys: {@code ok}, {@code changedFiles} and {@code deletedFiles} (paths relative to
     * {@code baseDir}, sorted), {@code warnings}, {@code error} (when not ok), {@code millis},
     * {@code parseMillis}, {@code reparsedFiles}, {@code totalFiles}.
     */
    @Override
    public synchronized Map<String, Object> apply(Map<String, Object> request) {
        long startedAt = System.nanoTime();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // Recipe classes named in YAML are looked up through the context loader in places.
        thread.setContextClassLoader(RewriteService.class.getClassLoader());
        Map<String, Object> response = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        try {
            ExecutionContext ctx = new InMemoryExecutionContext(
                    failure -> warnings.add(String.valueOf(failure.getMessage())));
            long parseStarted = System.nanoTime();
            SyncResult synced = sync(ctx, warnings);
            response.put("parseMillis", (System.nanoTime() - parseStarted) / 1_000_000);
            response.put("reparsedFiles", synced.reparsed);
            response.put("totalFiles", synced.trees.size());

            String op = String.valueOf(request.getOrDefault("op", "run"));
            if ("warm".equals(op)) {
                warmRecipes(synced.trees, ctx);
                response.put("ok", true);
                response.put("changedFiles", List.of());
                response.put("deletedFiles", List.of());
            } else if ("run".equals(op)) {
                run(request, synced.trees, ctx, warnings, response);
            } else {
                response.put("ok", false);
                response.put("error", "unknown op: " + op);
            }
        } catch (RuntimeException | IOException | LinkageError failure) {
            response.put("ok", false);
            response.put("error", describe(failure));
            response.putIfAbsent("changedFiles", List.of());
            response.putIfAbsent("deletedFiles", List.of());
        } finally {
            thread.setContextClassLoader(previous);
        }
        response.put("warnings", warnings);
        response.put("millis", (System.nanoTime() - startedAt) / 1_000_000);
        return response;
    }

    private record SyncResult(List<SourceFile> trees, int reparsed) {
    }

    /**
     * Brings the cache in line with the disk and returns every tree, strongly held for the request.
     *
     * <p>Reparsed: files that are new or whose size or mtime moved; files whose tree the collector
     * reclaimed; and the <em>dependents</em> of every changed or deleted file — cached files in the
     * same package, files that refer to a type the changed file declared, and files with an import
     * that did not resolve last time. Dependents are reparsed in the same javac batch as the files
     * they depend on, so their type attribution comes from the new source rather than from a class
     * file that may not have been recompiled yet. Without that, a signature change in one file
     * leaves stale method types in the files that call it, and a later rename silently skips them.
     * This errs toward reparsing too much: a whole package for any change inside it.
     */
    private SyncResult sync(ExecutionContext ctx, List<String> warnings) throws IOException {
        Map<Path, SourceRoot> onDisk = listSources();

        Map<Path, SourceFile> held = new HashMap<>();
        Set<Path> reparse = new LinkedHashSet<>();
        Set<String> touchedTypes = new HashSet<>();
        Set<String> touchedPackages = new HashSet<>();

        for (Map.Entry<Path, SourceRoot> file : onDisk.entrySet()) {
            Path path = file.getKey();
            LstCache.Entry entry = cache.get(path);
            BasicFileAttributes stat = Files.readAttributes(path, BasicFileAttributes.class);
            if (entry == null || !entry.matches(stat.size(), stat.lastModifiedTime().toMillis())) {
                reparse.add(path);
                touchedPackages.add(file.getValue().packageKey(path));
                if (entry != null) {
                    touchedTypes.addAll(entry.declaredTypes);
                }
                continue;
            }
            SourceFile tree = entry.tree();
            if (tree == null) {
                reparse.add(path); // evicted, unchanged: no dependents to revisit
            } else {
                held.put(path, tree);
            }
        }
        for (LstCache.Entry gone : new ArrayList<>(cache.entries())) {
            if (!onDisk.containsKey(gone.path)) {
                touchedTypes.addAll(gone.declaredTypes);
                touchedPackages.add(gone.packageKey);
                cache.remove(gone.path);
            }
        }
        if (!touchedTypes.isEmpty() || !touchedPackages.isEmpty()) {
            for (LstCache.Entry entry : cache.entries()) {
                if (!reparse.contains(entry.path) && entry.dependsOn(touchedTypes, touchedPackages)) {
                    reparse.add(entry.path);
                    held.remove(entry.path);
                }
            }
        }

        if (!reparse.isEmpty()) {
            log.accept("[jiro] rewrite: parsing " + reparse.size() + " of " + onDisk.size()
                    + " source files");
            for (SourceFile parsed : parse(new ArrayList<>(reparse), ctx)) {
                Path path = baseDir.resolve(parsed.getSourcePath()).normalize();
                SourceRoot root = onDisk.get(path);
                if (root == null) {
                    continue;
                }
                if (parsed instanceof ParseError) {
                    warnings.add("could not parse " + parsed.getSourcePath());
                }
                SourceFile marked = parsed.withMarkers(
                        parsed.getMarkers().setByType(sourceSets().get(root.test ? "test" : "main")));
                cache.put(path, root.test, root.packageKey(path), marked);
                held.put(path, marked);
            }
            styleStale = true;
        }

        List<SourceFile> trees = new ArrayList<>(held.size());
        for (Map.Entry<Path, SourceFile> entry : held.entrySet()) {
            trees.add(entry.getValue());
        }
        trees.sort((left, right) -> left.getSourcePath().compareTo(right.getSourcePath()));
        return new SyncResult(withCurrentMarkers(trees), reparse.size());
    }

    private List<SourceFile> parse(List<Path> paths, ExecutionContext ctx) {
        lock.lock();
        try {
            // A fresh parser, and so a fresh type cache, per batch: a cache shared across batches
            // would hand back the JavaType of a class as it was before the edit being reparsed.
            JavaParser parser = JavaParser.fromJavaVersion()
                    .classpath(testClasspath)
                    .logCompilationWarningsAndErrors(false)
                    .build();
            ParsingExecutionContextView.view(ctx).setCharset(charset);
            try (Stream<SourceFile> parsed = parser.parse(paths, baseDir, ctx)) {
                return parsed.toList();
            } finally {
                ParsingExecutionContextView.view(ctx).setCharset(null);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gives every tree the current style and source-set markers, as rewrite-maven-plugin does on a
     * fresh parse: one {@link Autodetect} sampled over all Java sources, and one
     * {@link JavaSourceSet} per scope. Both are recomputed only when the set of trees moved.
     */
    private List<SourceFile> withCurrentMarkers(List<SourceFile> trees) {
        if (styleStale || style == null) {
            Autodetect.Detector detector = Autodetect.detector();
            for (SourceFile tree : trees) {
                if (tree instanceof J.CompilationUnit) {
                    detector.sample(tree);
                }
            }
            style = detector.build();
            styleStale = false;
        }
        List<SourceFile> marked = new ArrayList<>(trees.size());
        for (SourceFile tree : trees) {
            if (tree instanceof J.CompilationUnit
                    && tree.getMarkers().findFirst(Autodetect.class).orElse(null) != style) {
                tree = tree.withMarkers(tree.getMarkers().setByType(style));
            }
            JavaSourceSet set = tree.getMarkers().findFirst(JavaSourceSet.class).orElse(null);
            if (set != null && sourceSets().get(set.getName()) != set) {
                tree = tree.withMarkers(tree.getMarkers().setByType(sourceSets().get(set.getName())));
            }
            marked.add(tree);
        }
        return marked;
    }

    private Map<String, JavaSourceSet> sourceSets() {
        if (sourceSets == null) {
            sourceSets = new HashMap<>();
            sourceSets.put("main", JavaSourceSet.build("main", compileClasspath));
            sourceSets.put("test", JavaSourceSet.build("test", testClasspath));
        }
        return sourceSets;
    }

    /**
     * Runs the common refactoring recipes once over every tree, aimed at names that cannot exist,
     * and throws the (empty) result away. Parsing is only half of a cold first request; the other
     * half is loading and interpreting the recipe and visitor classes, and that is what this pays
     * for in advance. Nothing is written.
     */
    private static final String WARM_RECIPE = String.join("\n",
            "type: specs.openrewrite.org/v1beta/recipe",
            "name: jiro.Warm",
            "recipeList:",
            "  - org.openrewrite.java.ChangeType:",
            "      oldFullyQualifiedTypeName: jiro.warm.Absent",
            "      newFullyQualifiedTypeName: jiro.warm.Absent2",
            "      ignoreDefinition: false",
            "  - org.openrewrite.java.ChangeMethodName:",
            "      methodPattern: jiro.warm.Absent absent(..)",
            "      newMethodName: absent2",
            "      matchOverrides: true",
            "  - org.openrewrite.java.ChangePackage:",
            "      oldPackageName: jiro.warm.absent",
            "      newPackageName: jiro.warm.absent2",
            "      recursive: true",
            "  - org.openrewrite.java.AddOrUpdateAnnotationAttribute:",
            "      annotationType: jiro.warm.Absent",
            "      attributeName: name",
            "      attributeValue: b",
            "      oldAttributeValue: a",
            "");

    private void warmRecipes(List<SourceFile> trees, ExecutionContext ctx) {
        Environment environment = Environment.builder()
                .load(new YamlResourceLoader(
                        new ByteArrayInputStream(WARM_RECIPE.getBytes(StandardCharsets.UTF_8)),
                        URI.create("jiro:warm"), new Properties(), RewriteService.class.getClassLoader()))
                .build();
        environment.activateRecipes("jiro.Warm").run(new InMemoryLargeSourceSet(trees), ctx);
    }

    private void run(Map<String, Object> request, List<SourceFile> trees, ExecutionContext ctx,
                     List<String> warnings, Map<String, Object> response) throws IOException {
        String yaml = (String) request.get("recipeYaml");
        if (yaml == null || yaml.isBlank()) {
            fail(response, "the request carries no recipe");
            return;
        }
        Environment environment = Environment.builder()
                .load(new YamlResourceLoader(
                        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                        URI.create("jiro:rewrite-request"), new Properties(),
                        RewriteService.class.getClassLoader()))
                .build();
        String name = (String) request.get("recipeName");
        if (name == null || name.isBlank()) {
            List<Recipe> declared = environment.listRecipes();
            if (declared.size() != 1) {
                fail(response, "no recipe name given and the YAML declares " + declared.size()
                        + " recipes; name the one to run");
                return;
            }
            name = declared.get(0).getName();
        }
        Recipe recipe = environment.activateRecipes(name);
        Validated<Object> validation = recipe.validate(ctx);
        if (!validation.isValid()) {
            List<String> problems = new ArrayList<>();
            for (Validated.Invalid<Object> failure : validation.failures()) {
                problems.add(failure.getProperty() + ": " + failure.getMessage());
            }
            fail(response, "invalid recipe " + name + ": " + String.join("; ", problems));
            return;
        }
        response.put("recipe", name);

        long runStarted = System.nanoTime();
        List<Result> results = recipe.run(new InMemoryLargeSourceSet(trees), ctx)
                .getChangeset().getAllResults();
        response.put("runMillis", (System.nanoTime() - runStarted) / 1_000_000);

        for (Result result : results) {
            String error = firstRecipeError(result);
            if (error != null) {
                fail(response, error);
                return;
            }
        }
        write(results, response);
    }

    /**
     * Applies results to disk the way rewrite-maven-plugin's {@code run} goal does — generated,
     * deleted, moved, then modified in place, then the directories a move emptied — and stores the
     * resulting trees so the next request does not reparse the files it just wrote.
     */
    private void write(List<Result> results, Map<String, Object> response) throws IOException {
        Set<String> changed = new java.util.TreeSet<>();
        Set<String> deleted = new java.util.TreeSet<>();
        Set<Path> maybeEmpty = new LinkedHashSet<>();
        long writeStarted = System.nanoTime();

        for (Result result : results) {
            SourceFile before = result.getBefore();
            SourceFile after = result.getAfter();
            if (before == null && after == null) {
                continue;
            }
            if (before != null) {
                Path beforePath = baseDir.resolve(before.getSourcePath()).normalize();
                boolean moved = after == null || !before.getSourcePath().equals(after.getSourcePath());
                if (moved) {
                    Files.deleteIfExists(beforePath);
                    cache.remove(beforePath);
                    maybeEmpty.add(beforePath.getParent());
                    deleted.add(relative(beforePath));
                }
            }
            if (after == null) {
                continue;
            }
            Path afterPath = baseDir.resolve(after.getSourcePath()).normalize();
            boolean textChanged = before == null
                    || !before.getSourcePath().equals(after.getSourcePath())
                    || !print(before).equals(print(after));
            if (textChanged) {
                Files.createDirectories(afterPath.getParent());
                Charset target = after.getCharset() == null ? StandardCharsets.UTF_8 : after.getCharset();
                Files.writeString(afterPath, print(after), target);
                changed.add(relative(afterPath));
            }
            SourceRoot root = rootOf(afterPath);
            if (root != null) {
                cache.put(afterPath, root.test, root.packageKey(afterPath), after);
            }
            // The plugin's ChangeType also renames types inside the scope's JavaSourceSet; keep the
            // newest one so every tree sees the same view next time.
            after.getMarkers().findFirst(JavaSourceSet.class)
                    .ifPresent(set -> sourceSets().put(set.getName(), set));
        }
        for (Path directory : maybeEmpty) {
            if (Files.isDirectory(directory)) {
                try (Stream<Path> contents = Files.list(directory)) {
                    if (contents.findAny().isEmpty()) {
                        Files.delete(directory);
                    }
                }
            }
        }
        if (!results.isEmpty()) {
            styleStale = true;
        }
        response.put("writeMillis", (System.nanoTime() - writeStarted) / 1_000_000);
        response.put("ok", true);
        response.put("changedFiles", new ArrayList<>(changed));
        response.put("deletedFiles", new ArrayList<>(deleted));
    }

    private static String print(SourceFile file) {
        return file.printAll(new PrintOutputCapture<>(0, SANITIZED));
    }

    /** Same as rewrite-maven-plugin's: only search results survive printing. */
    private static final PrintOutputCapture.MarkerPrinter SANITIZED = new PrintOutputCapture.MarkerPrinter() {
        @Override
        public String beforeSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
            return marker instanceof SearchResult
                    ? PrintOutputCapture.MarkerPrinter.DEFAULT.beforeSyntax(marker, cursor, commentWrapper)
                    : "";
        }

        @Override
        public String afterSyntax(Marker marker, Cursor cursor, UnaryOperator<String> commentWrapper) {
            return marker instanceof SearchResult
                    ? PrintOutputCapture.MarkerPrinter.DEFAULT.afterSyntax(marker, cursor, commentWrapper)
                    : "";
        }
    };

    private static String firstRecipeError(Result result) {
        if (result.getAfter() == null) {
            return null;
        }
        String[] found = new String[1];
        new TreeVisitor<Tree, Integer>() {
            @Override
            public Tree preVisit(Tree tree, Integer unused) {
                if (found[0] == null) {
                    tree.getMarkers().findFirst(Markup.Error.class).ifPresent(error ->
                            found[0] = "error while visiting " + result.getAfter().getSourcePath()
                                    + ": " + error.getDetail());
                }
                return tree;
            }
        }.visit(result.getAfter(), 0);
        return found[0];
    }

    private record SourceRoot(Path root, boolean test) {
        String packageKey(Path file) {
            Path parent = root.relativize(file).getParent();
            return parent == null ? "" : parent.toString().replace(java.io.File.separatorChar, '.');
        }
    }

    private Map<Path, SourceRoot> listSources() throws IOException {
        Map<Path, SourceRoot> sources = new LinkedHashMap<>();
        for (Path root : mainRoots) {
            collect(new SourceRoot(root, false), sources);
        }
        for (Path root : testRoots) {
            collect(new SourceRoot(root, true), sources);
        }
        return sources;
    }

    private static void collect(SourceRoot root, Map<Path, SourceRoot> into) throws IOException {
        if (!Files.isDirectory(root.root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root.root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(path -> into.putIfAbsent(path.toAbsolutePath().normalize(), root));
        } catch (UncheckedIOException walkFailure) {
            throw walkFailure.getCause();
        }
    }

    private SourceRoot rootOf(Path file) {
        for (Path root : mainRoots) {
            if (file.startsWith(root)) {
                return new SourceRoot(root, false);
            }
        }
        for (Path root : testRoots) {
            if (file.startsWith(root)) {
                return new SourceRoot(root, true);
            }
        }
        return null;
    }

    private String relative(Path path) {
        return baseDir.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }

    private static List<Path> absolute(List<Path> paths) {
        List<Path> absolute = new ArrayList<>(paths.size());
        for (Path path : paths) {
            absolute.add(path.toAbsolutePath().normalize());
        }
        return absolute;
    }

    private static void fail(Map<String, Object> response, String error) {
        response.put("ok", false);
        response.put("error", error);
        response.putIfAbsent("changedFiles", List.of());
        response.putIfAbsent("deletedFiles", List.of());
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}

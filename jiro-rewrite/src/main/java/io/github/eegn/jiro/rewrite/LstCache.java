package io.github.eegn.jiro.rewrite;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.openrewrite.SourceFile;
import org.openrewrite.java.internal.TypesInUse;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;

/**
 * Parsed trees keyed by absolute path, each remembered with the size and mtime it was parsed (or
 * written) at, so a request can tell which files moved on disk since.
 *
 * <p>Trees are held through {@link SoftReference}s. An LST for a mid-sized Spring Boot service is
 * tens of megabytes and lives inside the Maven JVM next to the compiler and the fork supervisor; a
 * hard cap would be a number nobody can choose well, whereas soft references let the collector
 * reclaim trees exactly when the heap is under pressure. A reclaimed tree is simply reparsed on the
 * next request — slower, never wrong, because its file has not changed and so nothing that depends
 * on it has to be revisited.
 *
 * <p>What survives eviction is the bookkeeping that decides <em>which</em> files to reparse: the
 * stat, the package, the top-level types the file declares and the types it refers to. Those are a
 * few strings per file, and they are what lets a change to one file pull in its dependents even
 * when the dependents' trees are long gone.
 */
final class LstCache {

    /** One file's cached state. {@code tree} may have been cleared by the collector. */
    static final class Entry {
        final Path path;
        final boolean test;
        final String packageKey;
        final long size;
        final long mtimeMillis;
        final Set<String> declaredTypes;
        final Set<String> referencedTypes;
        final boolean unresolvedImports;
        private final SoftReference<SourceFile> tree;

        Entry(Path path, boolean test, String packageKey, long size, long mtimeMillis,
              Set<String> declaredTypes, Set<String> referencedTypes, boolean unresolvedImports,
              SourceFile tree) {
            this.path = path;
            this.test = test;
            this.packageKey = packageKey;
            this.size = size;
            this.mtimeMillis = mtimeMillis;
            this.declaredTypes = declaredTypes;
            this.referencedTypes = referencedTypes;
            this.unresolvedImports = unresolvedImports;
            this.tree = new SoftReference<>(tree);
        }

        SourceFile tree() {
            return tree.get();
        }

        boolean matches(long size, long mtimeMillis) {
            return this.size == size && this.mtimeMillis == mtimeMillis;
        }

        /** True if this file mentions, or could mention, anything declared in the given types or package. */
        boolean dependsOn(Set<String> types, Set<String> packages) {
            if (unresolvedImports || packages.contains(packageKey)) {
                return true;
            }
            for (String type : referencedTypes) {
                if (types.contains(type)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Map<Path, Entry> entries = new HashMap<>();

    Entry get(Path path) {
        return entries.get(path);
    }

    Collection<Entry> entries() {
        return entries.values();
    }

    int size() {
        return entries.size();
    }

    void remove(Path path) {
        entries.remove(path);
    }

    /** Stores a tree with the stat of the file as it is on disk right now. */
    void put(Path path, boolean test, String packageKey, SourceFile tree) throws IOException {
        BasicFileAttributes stat = Files.readAttributes(path, BasicFileAttributes.class);
        Set<String> declared = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        boolean unresolved = false;
        if (tree instanceof JavaSourceFile javaSource) {
            for (J.ClassDeclaration type : javaSource.getClasses()) {
                if (type.getType() != null) {
                    declared.add(topLevel(type.getType().getFullyQualifiedName()));
                }
            }
            collectReferences(javaSource, referenced);
            for (J.Import anImport : javaSource.getImports()) {
                JavaType type = anImport.getQualid().getType();
                if (!anImport.isStatic() && !"*".equals(anImport.getQualid().getSimpleName())
                        && (type == null || type instanceof JavaType.Unknown)) {
                    unresolved = true;
                }
            }
        }
        declared.add(pathDerivedType(packageKey, path));
        entries.put(path, new Entry(path, test, packageKey, stat.size(),
                stat.lastModifiedTime().toMillis(), declared, referenced, unresolved, tree));
    }

    /**
     * Every top-level type the file uses: types, the declaring types of methods it calls and of
     * fields it reads. Built fresh rather than from the tree's memoised {@code TypesInUse}, because
     * a tree a recipe produced may still carry the memo of the tree it was derived from.
     */
    private static void collectReferences(JavaSourceFile source, Set<String> into) {
        TypesInUse inUse = TypesInUse.build(source);
        for (JavaType type : inUse.getTypesInUse()) {
            addType(type, into);
        }
        for (JavaType.Method method : inUse.getUsedMethods()) {
            addType(method.getDeclaringType(), into);
        }
        for (JavaType.Variable variable : inUse.getVariables()) {
            addType(variable.getOwner(), into);
        }
    }

    private static void addType(JavaType type, Set<String> into) {
        while (type instanceof JavaType.Array array) {
            type = array.getElemType();
        }
        if (type instanceof JavaType.FullyQualified qualified && !(type instanceof JavaType.Unknown)) {
            into.add(topLevel(qualified.getFullyQualifiedName()));
        }
    }

    static String topLevel(String fullyQualifiedName) {
        int nested = fullyQualifiedName.indexOf('$');
        return nested < 0 ? fullyQualifiedName : fullyQualifiedName.substring(0, nested);
    }

    /** The type a file is named after, which is all that is known about a file whose tree is gone. */
    static String pathDerivedType(String packageKey, Path path) {
        String file = path.getFileName().toString();
        String simple = file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
        return packageKey.isEmpty() ? simple : packageKey + "." + simple;
    }
}

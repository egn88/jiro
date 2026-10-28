package io.github.eegn.jiro.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RewriteServiceTest {

    @TempDir
    Path project;

    private Path main;
    private Path test;
    private Path classes;

    @BeforeEach
    void layout() throws IOException {
        main = Files.createDirectories(project.resolve("src/main/java"));
        test = Files.createDirectories(project.resolve("src/test/java"));
        classes = Files.createDirectories(project.resolve("target/classes"));
    }

    private RewriteService service() {
        return new RewriteService(Map.of(
                "baseDir", project,
                "mainSourceRoots", List.of(main),
                "testSourceRoots", List.of(test),
                "compileClasspath", List.of(classes),
                "testClasspath", List.of(classes)));
    }

    private Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /** Rewrites a file and pushes its mtime forward, as a later save would on a coarse clock. */
    private void edit(Path file, String content) throws IOException {
        long before = Files.getLastModifiedTime(file).toMillis();
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 2000));
    }

    private static Map<String, Object> run(RewriteService service, String recipeList) {
        String yaml = """
                type: specs.openrewrite.org/v1beta/recipe
                name: test.Recipe
                displayName: test
                recipeList:
                """ + recipeList;
        return service.apply(Map.of("op", "run", "recipeYaml", yaml, "recipeName", "test.Recipe"));
    }

    private static String renameMethod(String pattern, String newName) {
        return "  - org.openrewrite.java.ChangeMethodName:\n"
                + "      methodPattern: \"" + pattern + "\"\n"
                + "      newMethodName: " + newName + "\n";
    }

    private void compile(Path... sources) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
        for (Path source : sources) {
            arguments.add(source.toString());
        }
        assertEquals(0, javac.run(null, null, null, arguments.toArray(String[]::new)));
    }

    @Test
    void renamesATypeAcrossFilesAndMovesItsSource() throws IOException {
        write(main, "a/Customer.java", "package a;\n\npublic class Customer {\n}\n");
        write(test, "b/CustomerTest.java",
                "package b;\n\nimport a.Customer;\n\nclass CustomerTest {\n    Customer customer;\n}\n");

        Map<String, Object> response = run(service(),
                "  - org.openrewrite.java.ChangeType:\n"
                        + "      oldFullyQualifiedTypeName: a.Customer\n"
                        + "      newFullyQualifiedTypeName: a.Diner\n"
                        + "      ignoreDefinition: false\n");

        assertEquals(true, response.get("ok"), String.valueOf(response.get("error")));
        assertEquals(List.of("src/main/java/a/Diner.java", "src/test/java/b/CustomerTest.java"),
                response.get("changedFiles"));
        assertEquals(List.of("src/main/java/a/Customer.java"), response.get("deletedFiles"));
        assertFalse(Files.exists(main.resolve("a/Customer.java")));
        assertTrue(Files.readString(main.resolve("a/Diner.java")).contains("public class Diner"));
        assertTrue(Files.readString(test.resolve("b/CustomerTest.java")).contains("Diner customer;"));
    }

    @Test
    void doesNotReparseWhatItWroteItself() throws IOException {
        write(main, "a/A.java", "package a;\n\npublic class A {\n    public void one() {\n    }\n}\n");
        write(main, "b/B.java", "package b;\n\nclass B {\n    void use() {\n        new a.A().one();\n    }\n}\n");
        RewriteService service = service();

        Map<String, Object> warm = service.apply(Map.of("op", "warm"));
        assertEquals(2, warm.get("reparsedFiles"));

        Map<String, Object> first = run(service, renameMethod("a.A one()", "two"));
        assertEquals(true, first.get("ok"), String.valueOf(first.get("error")));
        assertEquals(0, first.get("reparsedFiles"));
        assertEquals(List.of("src/main/java/a/A.java", "src/main/java/b/B.java"), first.get("changedFiles"));

        Map<String, Object> second = run(service, renameMethod("a.A two()", "three"));
        assertEquals(true, second.get("ok"), String.valueOf(second.get("error")));
        assertEquals(0, second.get("reparsedFiles"), "files the service wrote are cached as written");
        assertTrue(Files.readString(main.resolve("b/B.java")).contains("new a.A().three();"));
    }

    /**
     * B is untouched, but an overload added to A changes which method B's call binds to. Reparsing
     * only A would leave B's cached tree pointing at m(Object), and renaming m(String) would skip B.
     */
    @Test
    void reparsesDependentsWhenADeclarationChanges() throws IOException {
        Path a = write(main, "a/A.java", "package a;\n\npublic class A {\n    public void m(Object o) {\n    }\n}\n");
        write(main, "b/B.java", "package b;\n\nclass B {\n    void use() {\n        new a.A().m(\"x\");\n    }\n}\n");
        RewriteService service = service();
        service.apply(Map.of("op", "warm"));

        edit(a, "package a;\n\npublic class A {\n    public void m(Object o) {\n    }\n\n"
                + "    public void m(String s) {\n    }\n}\n");

        Map<String, Object> response = run(service, renameMethod("a.A m(String)", "n"));
        assertEquals(true, response.get("ok"), String.valueOf(response.get("error")));
        assertTrue(Files.readString(main.resolve("b/B.java")).contains("new a.A().n(\"x\");"),
                Files.readString(main.resolve("b/B.java")));
        assertEquals(2, response.get("reparsedFiles"));
    }

    /**
     * Both sides edited since the last request, and the compiled A on the classpath predates the
     * new method. Parsing them in one batch attributes B's call from A's source, not its stale class.
     */
    @Test
    void renamesAfterEditingAReferencingFileAgainstStaleClasses() throws IOException {
        Path a = write(main, "a/A.java", "package a;\n\npublic class A {\n}\n");
        Path b = write(main, "b/B.java", "package b;\n\nclass B {\n}\n");
        compile(a);
        RewriteService service = service();
        service.apply(Map.of("op", "warm"));

        edit(a, "package a;\n\npublic class A {\n    public void foo() {\n    }\n}\n");
        edit(b, "package b;\n\nclass B {\n    void use() {\n        new a.A().foo();\n    }\n}\n");

        Map<String, Object> response = run(service, renameMethod("a.A foo()", "bar"));
        assertEquals(true, response.get("ok"), String.valueOf(response.get("error")));
        assertTrue(Files.readString(b).contains("new a.A().bar();"), Files.readString(b));
    }

    /** Only the referencing file changed; the referenced type comes from target/classes. */
    @Test
    void renamesAfterEditingOnlyTheReferencingFile() throws IOException {
        Path a = write(main, "a/A.java", "package a;\n\npublic class A {\n    public void foo() {\n    }\n}\n");
        Path b = write(main, "b/B.java", "package b;\n\nclass B {\n}\n");
        compile(a);
        RewriteService service = service();
        service.apply(Map.of("op", "warm"));

        edit(b, "package b;\n\nclass B {\n    void use() {\n        new a.A().foo();\n    }\n}\n");

        Map<String, Object> response = run(service, renameMethod("a.A foo()", "bar"));
        assertEquals(true, response.get("ok"), String.valueOf(response.get("error")));
        assertEquals(1, response.get("reparsedFiles"));
        assertTrue(Files.readString(b).contains("new a.A().bar();"), Files.readString(b));
        assertTrue(Files.readString(a).contains("public void bar()"));
    }

    @Test
    void reportsAnUnknownRecipeWithoutTouchingFiles() throws IOException {
        Path a = write(main, "a/A.java", "package a;\n\npublic class A {\n}\n");
        String yaml = "type: specs.openrewrite.org/v1beta/recipe\nname: test.Recipe\nrecipeList:\n"
                + "  - org.openrewrite.java.ChangeType:\n"
                + "      oldFullyQualifiedTypeName: a.A\n"
                + "      newFullyQualifiedTypeName: a.Z\n";

        Map<String, Object> response = service().apply(
                Map.of("op", "run", "recipeYaml", yaml, "recipeName", "test.Missing"));

        assertEquals(false, response.get("ok"));
        assertTrue(String.valueOf(response.get("error")).contains("test.Missing"),
                String.valueOf(response.get("error")));
        assertTrue(Files.exists(a));
    }

    @Test
    void picksTheOnlyDeclaredRecipeWhenNoneIsNamed() throws IOException {
        write(main, "a/A.java", "package a;\n\npublic class A {\n}\n");
        String yaml = "type: specs.openrewrite.org/v1beta/recipe\nname: test.Only\nrecipeList:\n"
                + "  - org.openrewrite.java.ChangeType:\n"
                + "      oldFullyQualifiedTypeName: a.A\n"
                + "      newFullyQualifiedTypeName: a.Z\n"
                + "      ignoreDefinition: false\n";

        Map<String, Object> response = service().apply(Map.of("op", "run", "recipeYaml", yaml));

        assertEquals(true, response.get("ok"), String.valueOf(response.get("error")));
        assertEquals("test.Only", response.get("recipe"));
        assertTrue(Files.exists(main.resolve("a/Z.java")));
    }
}

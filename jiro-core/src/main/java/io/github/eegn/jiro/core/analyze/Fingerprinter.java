package io.github.eegn.jiro.core.analyze;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Turns {@code .class} files into {@link ClassFingerprint}s.
 *
 * <p>The digest is taken over normalised bytecode with debug information stripped, which is the
 * property the whole tool leans on: reformatting a file, renaming a local variable, adding a
 * comment or moving a method down the file all recompile to the same digest and therefore select
 * no tests at all. Only a change that a JVM could observe moves a hash.
 */
public final class Fingerprinter {

    /** Digest of every class file under {@code classesDirectory}, keyed by internal name. */
    public Map<String, ClassFingerprint> scan(Path classesDirectory) throws IOException {
        Map<String, ClassFingerprint> fingerprints = new HashMap<>();
        if (!Files.isDirectory(classesDirectory)) {
            return fingerprints;
        }
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            List<Path> classFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .toList();
            for (Path classFile : classFiles) {
                ClassFingerprint fingerprint = fingerprint(classFile);
                if (fingerprint != null) {
                    fingerprints.put(fingerprint.internalName(), fingerprint);
                }
            }
        }
        return fingerprints;
    }

    /** @return the fingerprint, or {@code null} if the file could not be parsed as a class */
    public ClassFingerprint fingerprint(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile)) {
            ClassNode node = new ClassNode();
            // SKIP_DEBUG drops line numbers and local variable names; SKIP_FRAMES drops stack map
            // frames, which are derived. Both are noise that would otherwise make every edit in a
            // file look like a change to every method below it.
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return toFingerprint(node);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException unreadable) {
            return null;
        }
    }

    private ClassFingerprint toFingerprint(ClassNode node) {
        Set<String> references = new HashSet<>();
        addTypeReference(references, node.superName);
        if (node.interfaces != null) {
            node.interfaces.forEach(references::add);
        }

        Map<String, String> methodHashes = new TreeMap<>();
        for (MethodNode method : node.methods) {
            methodHashes.put(
                    node.name + '#' + method.name + method.desc,
                    sha256(methodBody(method, references)));
        }
        for (FieldNode field : node.fields) {
            addDescriptorReferences(references, field.desc);
        }
        references.remove(node.name);

        return new ClassFingerprint(node.name, sha256(abi(node)), methodHashes, references);
    }

    /**
     * Everything another class can depend on without jiro seeing a call edge: supertypes, member
     * signatures and annotations. Private members are excluded because changing them cannot break
     * a caller that compiled successfully.
     */
    private String abi(ClassNode node) {
        StringBuilder text = new StringBuilder();
        text.append(node.access).append(' ').append(node.name)
                .append(" extends ").append(node.superName)
                .append(" implements ").append(node.interfaces);
        appendAnnotations(text, node.visibleAnnotations, node.invisibleAnnotations);

        List<String> members = new ArrayList<>();
        for (FieldNode field : node.fields) {
            if (!isPrivate(field.access)) {
                StringBuilder member = new StringBuilder();
                member.append("F").append(field.access).append(' ')
                        .append(field.name).append(' ').append(field.desc)
                        .append(' ').append(field.value);
                appendAnnotations(member, field.visibleAnnotations, field.invisibleAnnotations);
                members.add(member.toString());
            }
        }
        for (MethodNode method : node.methods) {
            if (!isPrivate(method.access)) {
                StringBuilder member = new StringBuilder();
                member.append("M").append(method.access).append(' ')
                        .append(method.name).append(method.desc)
                        .append(" throws ").append(method.exceptions);
                appendAnnotations(member, method.visibleAnnotations, method.invisibleAnnotations);
                members.add(member.toString());
            }
        }
        // Sorted because javac is free to emit members in any order across compilations.
        members.sort(String::compareTo);
        text.append(members);
        return text.toString();
    }

    private String methodBody(MethodNode method, Set<String> references) {
        StringBuilder text = new StringBuilder();
        text.append(method.access).append(' ').append(method.name).append(method.desc)
                .append(" throws ").append(method.exceptions);
        // Annotations belong in the body digest as well: flipping @Transactional(readOnly = true)
        // changes what the method does without touching an instruction.
        appendAnnotations(text, method.visibleAnnotations, method.invisibleAnnotations);
        addDescriptorReferences(references, method.desc);

        Map<LabelNode, Integer> labelIds = indexLabels(method);
        for (AbstractInsnNode insn : method.instructions) {
            appendInstruction(text, insn, labelIds, references);
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                text.append("|TRY ").append(block.type)
                        .append(' ').append(labelIds.get(block.start))
                        .append(' ').append(labelIds.get(block.end))
                        .append(' ').append(labelIds.get(block.handler));
                addTypeReference(references, block.type);
            }
        }
        return text.toString();
    }

    private static Map<LabelNode, Integer> indexLabels(MethodNode method) {
        // Labels are object identities with no stable name, so they are renumbered in encounter
        // order. Jump targets then compare equal iff the control flow is genuinely the same.
        Map<LabelNode, Integer> labelIds = new HashMap<>();
        int next = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LabelNode label && !labelIds.containsKey(label)) {
                labelIds.put(label, next++);
            }
        }
        return labelIds;
    }

    private void appendInstruction(StringBuilder text,
                                   AbstractInsnNode insn,
                                   Map<LabelNode, Integer> labelIds,
                                   Set<String> references) {
        text.append('|').append(insn.getOpcode());
        if (insn instanceof LabelNode label) {
            text.append("L").append(labelIds.get(label));
        } else if (insn instanceof FieldInsnNode field) {
            text.append(field.owner).append('.').append(field.name).append(':').append(field.desc);
            addTypeReference(references, field.owner);
            addDescriptorReferences(references, field.desc);
        } else if (insn instanceof MethodInsnNode call) {
            text.append(call.owner).append('.').append(call.name).append(call.desc).append(call.itf);
            addTypeReference(references, call.owner);
            addDescriptorReferences(references, call.desc);
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
            // Lambdas and method references land here; the bootstrap arguments name the
            // synthetic target, which is what makes a changed lambda body visible.
            text.append(indy.name).append(indy.desc).append(indy.bsm);
            for (Object argument : indy.bsmArgs) {
                text.append(';').append(argument);
                if (argument instanceof Handle handle) {
                    addTypeReference(references, handle.getOwner());
                }
            }
            addDescriptorReferences(references, indy.desc);
        } else if (insn instanceof TypeInsnNode type) {
            text.append(type.desc);
            addTypeReference(references, type.desc);
        } else if (insn instanceof VarInsnNode variable) {
            text.append(variable.var);
        } else if (insn instanceof IntInsnNode operand) {
            text.append(operand.operand);
        } else if (insn instanceof LdcInsnNode constant) {
            text.append(constant.cst);
            if (constant.cst instanceof Type type) {
                addDescriptorReferences(references, type.getDescriptor());
            }
        } else if (insn instanceof IincInsnNode increment) {
            text.append(increment.var).append('+').append(increment.incr);
        } else if (insn instanceof JumpInsnNode jump) {
            text.append("->").append(labelIds.get(jump.label));
        } else if (insn instanceof TableSwitchInsnNode table) {
            text.append(table.min).append('-').append(table.max)
                    .append(labelIds.get(table.dflt));
            table.labels.forEach(label -> text.append(',').append(labelIds.get(label)));
        } else if (insn instanceof LookupSwitchInsnNode lookup) {
            text.append(lookup.keys).append(labelIds.get(lookup.dflt));
            lookup.labels.forEach(label -> text.append(',').append(labelIds.get(label)));
        } else if (insn instanceof MultiANewArrayInsnNode array) {
            text.append(array.desc).append('x').append(array.dims);
            addDescriptorReferences(references, array.desc);
        }
    }

    private static void appendAnnotations(StringBuilder text,
                                          List<AnnotationNode> visible,
                                          List<AnnotationNode> invisible) {
        for (List<AnnotationNode> annotations : List.of(
                visible == null ? List.<AnnotationNode>of() : visible,
                invisible == null ? List.<AnnotationNode>of() : invisible)) {
            List<String> rendered = new ArrayList<>();
            for (AnnotationNode annotation : annotations) {
                rendered.add(annotation.desc + renderAnnotationValue(annotation.values));
            }
            rendered.sort(String::compareTo);
            text.append('@').append(rendered);
        }
    }

    /**
     * Renders an annotation value so that two scans of an unchanged class file always agree.
     *
     * <p>This has to be done by hand. ASM models an enum annotation value as a {@code String[]} of
     * descriptor and constant name, and array-valued attributes as nested arrays, so the obvious
     * {@code String.valueOf} bakes {@code [Ljava.lang.String;@1b6d3586} — an identity hash — into
     * the digest. Every annotation carrying an enum attribute then re-hashes on every scan, and a
     * comment-only edit reports the whole codebase as changed.
     */
    private static String renderAnnotationValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof AnnotationNode nested) {
            return nested.desc + renderAnnotationValue(nested.values);
        }
        if (value instanceof Type type) {
            return type.getDescriptor();
        }
        if (value instanceof List<?> items) {
            StringBuilder rendered = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    rendered.append(", ");
                }
                rendered.append(renderAnnotationValue(items.get(i)));
            }
            return rendered.append(']').toString();
        }
        if (value instanceof Object[] array) {
            StringBuilder rendered = new StringBuilder("{");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    rendered.append(", ");
                }
                rendered.append(renderAnnotationValue(array[i]));
            }
            return rendered.append('}').toString();
        }
        if (value.getClass().isArray()) {
            // Primitive arrays: byte[], int[], and friends from array-valued attributes.
            int length = java.lang.reflect.Array.getLength(value);
            StringBuilder rendered = new StringBuilder("{");
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    rendered.append(", ");
                }
                rendered.append(java.lang.reflect.Array.get(value, i));
            }
            return rendered.append('}').toString();
        }
        return String.valueOf(value);
    }

    private static void addTypeReference(Set<String> references, String internalNameOrDescriptor) {
        if (internalNameOrDescriptor == null) {
            return;
        }
        if (internalNameOrDescriptor.startsWith("[") || internalNameOrDescriptor.startsWith("L")) {
            addDescriptorReferences(references, internalNameOrDescriptor);
        } else {
            references.add(internalNameOrDescriptor);
        }
    }

    private static void addDescriptorReferences(Set<String> references, String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return;
        }
        try {
            if (descriptor.charAt(0) == '(') {
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    addObjectType(references, argument);
                }
                addObjectType(references, Type.getReturnType(descriptor));
            } else {
                addObjectType(references, Type.getType(descriptor));
            }
        } catch (IllegalArgumentException malformed) {
            // A descriptor jiro cannot parse only costs one firewall edge.
        }
    }

    private static void addObjectType(Set<String> references, Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() == Type.OBJECT) {
            references.add(element.getInternalName());
        }
    }

    private static boolean isPrivate(int access) {
        return (access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
        }
    }
}

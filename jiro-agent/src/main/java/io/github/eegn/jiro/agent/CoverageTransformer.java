package io.github.eegn.jiro.agent;

import io.github.eegn.jiro.runtime.CoverageRecorder;
import io.github.eegn.jiro.runtime.MethodRegistry;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects {@code CoverageRecorder.hit(id)} as the first instruction of every method body in the
 * classes the filter accepts.
 *
 * <p>Entry probes rather than per-branch probes: jiro only needs to know whether a test reached a
 * method, not which path it took through it, and one probe per method keeps the overhead low
 * enough to leave enabled continuously.
 */
public final class CoverageTransformer implements ClassFileTransformer {

    /**
     * Written into the instrumented bytecode as a literal. It must stay a literal: the shade
     * relocation that moves ASM out of the way would otherwise be free to rewrite a name derived
     * from {@code CoverageRecorder.class}, and the probe would call into nothing.
     */
    private static final String RECORDER = "io/github/eegn/jiro/runtime/CoverageRecorder";

    private final InstrumentationFilter filter;

    public CoverageTransformer(InstrumentationFilter filter) {
        this.filter = filter;
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (!filter.shouldInstrument(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // COMPUTE_MAXS because the probe pushes one int onto a stack the original frame may
            // have sized at zero. COMPUTE_FRAMES is deliberately avoided: it resolves common
            // superclasses by loading them, which deadlocks inside a transformer.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ProbeInjector(writer, className), 0);
            CoverageRecorder.ensureCapacity(MethodRegistry.size());
            return writer.toByteArray();
        } catch (Throwable failure) {
            // A class jiro cannot rewrite must still load unmodified; losing coverage for it
            // costs precision, throwing here would cost the user their JVM.
            System.err.println("[jiro] skipped instrumentation of " + className + ": " + failure);
            return null;
        }
    }

    private static final class ProbeInjector extends ClassVisitor {
        private final String owner;

        ProbeInjector(ClassVisitor delegate, String owner) {
            super(Opcodes.ASM9, delegate);
            this.owner = owner;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean hasBody = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            if (delegate == null || !hasBody) {
                return delegate;
            }
            int methodId = MethodRegistry.register(owner, name, descriptor);
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    visitLdcInsn(methodId);
                    visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "hit", "(I)V", false);
                }
            };
        }
    }
}

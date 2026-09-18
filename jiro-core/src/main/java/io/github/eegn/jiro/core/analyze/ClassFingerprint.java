package io.github.eegn.jiro.core.analyze;

import java.util.Map;
import java.util.Set;

/**
 * A structural summary of one compiled class, stable across recompilations that did not change
 * behaviour.
 *
 * @param internalName JVM internal name, e.g. {@code com/example/OrderService}
 * @param abiHash      digest of everything visible to other classes: supertypes, and the signature
 *                     and annotations of every non-private member. A change here can alter the
 *                     meaning of code jiro has no coverage for, so it triggers the static firewall.
 * @param methodHashes method key to digest of that method's normalised body
 * @param references   internal names this class mentions, used to invert into a firewall
 */
public record ClassFingerprint(String internalName,
                               String abiHash,
                               Map<String, String> methodHashes,
                               Set<String> references) {

    public ClassFingerprint {
        methodHashes = Map.copyOf(methodHashes);
        references = Set.copyOf(references);
    }
}

package org.tinycc;

/** Receives TinyCC errors and warnings on the calling compilation thread. */
@FunctionalInterface
public interface DiagnosticListener {
    void onDiagnostic(String diagnostic);
}

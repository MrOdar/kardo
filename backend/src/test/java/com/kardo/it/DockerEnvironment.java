package com.kardo.it;

import org.testcontainers.DockerClientFactory;

/** Comprueba si hay un demonio Docker al que pedirle contenedores. */
final class DockerEnvironment {

    /** La deteccion es cara, y la respuesta no cambia durante el build. */
    private static final boolean PRESENT = detect();

    private DockerEnvironment() {
    }

    static boolean isPresent() {
        return PRESENT;
    }

    private static boolean detect() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}

package com.kardo.support;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Huella del contenido de un archivo, usada para reconocer una recarga. */
public final class Sha256 {

    /** 8 KB: el archivo ya esta en disco, lo unico que importa es no inflar el heap. */
    private static final int BUFFER_SIZE = 8 * 1024;

    private Sha256() {
    }

    /**
     * Calcula el hash leyendo el flujo por bloques.
     *
     * <p>Nunca se materializa el archivo en memoria: el presupuesto de 512 MB del
     * contenedor es el mismo que impone el streaming de la ingesta.
     *
     * @param inputStream se consume por completo; el llamante lo cierra
     */
    public static String of(InputStream inputStream) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 es obligatorio en toda implementacion de la plataforma Java.
            throw new IllegalStateException("La JVM no ofrece SHA-256.", ex);
        }
    }
}

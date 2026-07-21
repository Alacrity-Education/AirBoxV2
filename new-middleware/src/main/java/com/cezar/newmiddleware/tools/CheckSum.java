package com.cezar.newmiddleware.tools;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

public class CheckSum {
    private CheckSum() {}

    /** Full SHA-256 hex of the exact string content. Callers truncate as needed. */
    public static String generateSHA256(String content) {
        return Hashing.sha256().hashString(content, StandardCharsets.UTF_8).toString();
    }
}

package com.cezar.newmiddleware.tools;

public class ApiKeySolver {

    private static final String SCHEME = "ApiKey ";

    public static String resolve(String authorization, String apiKeyHeader, String xApiKey) {
        String fromAuth = stripScheme(authorization);
        if (isPresent(fromAuth)) return fromAuth.trim();
        if (isPresent(apiKeyHeader)) return apiKeyHeader.trim();
        if (isPresent(xApiKey)) return xApiKey.trim();
        return null;
    }

    // "Authorization: ApiKey XXXX" carries a scheme prefix; the bare headers don't.
    private static String stripScheme(String authorization) {
        if (authorization == null) return null;
        String v = authorization.trim();
        if (v.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return v.substring(SCHEME.length());
        }
        return v;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}

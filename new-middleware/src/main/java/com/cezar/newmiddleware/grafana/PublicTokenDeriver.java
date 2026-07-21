package com.cezar.newmiddleware.grafana;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

public class PublicTokenDeriver {
    public static String derive(String secret, String dashboardUid) {
        try{
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")
            );
            return HexFormat.of().formatHex(
                    mac.doFinal(dashboardUid.getBytes(StandardCharsets.UTF_8))
            ).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

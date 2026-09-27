package dev.margintrace.margin_attribution_backend.report;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;

@Component
public class OnlyOfficeJwtSigner {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public OnlyOfficeJwtSigner(
            ObjectMapper objectMapper,
            @Value("${onlyoffice.jwt-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("ONLYOFFICE JWT secret must not be blank");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(Map<String, Object> payload) {
        try {
            String header = encode(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String body = encode(objectMapper.writeValueAsBytes(payload));
            String signingInput = header + "." + body;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String signature = encode(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign ONLYOFFICE configuration", exception);
        }
    }

    public String accessToken(String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return encode(mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create ONLYOFFICE access token", exception);
        }
    }

    private String encode(byte[] value) {
        return BASE64_URL.encodeToString(value);
    }
}

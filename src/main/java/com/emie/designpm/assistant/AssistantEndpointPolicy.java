package com.emie.designpm.assistant;

import com.emie.designpm.recommendation.service.VisualAiSettings;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only deployment configuration can authorize an exact private model service endpoint. */
@Component
public class AssistantEndpointPolicy {
    private final Set<String> trustedBases;

    public AssistantEndpointPolicy(@Value("${ASSISTANT_TRUSTED_BASE_URLS:}") String configuredBases) {
        Set<String> bases = new HashSet<>();
        try {
            if (configuredBases != null && !configuredBases.isBlank()) {
                if (configuredBases.chars().anyMatch(Character::isISOControl)) throw invalidEndpoint();
                for (String base : configuredBases.split(",", -1)) bases.add(privateBase(base.trim()));
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("ASSISTANT_TRUSTED_BASE_URLS 须为完整、固定的私网 IPv4 模型接口地址");
        }
        trustedBases = Set.copyOf(bases);
    }

    public void validate(String raw) {
        try {
            if (trustedBases.contains(privateBase(raw))) return;
        } catch (IllegalArgumentException invalid) {
            // Ordinary public endpoints continue through the original, restrictive policy.
        }
        try {
            VisualAiSettings.validateBaseUrl(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("AI 服务地址须为公网 HTTPS 地址或服务器已授权的内网代理");
        }
    }

    private static String privateBase(String raw) {
        if (raw == null
                || raw.isBlank()
                || raw.length() > 500
                || raw.chars().anyMatch(value -> Character.isISOControl(value) || Character.isWhitespace(value)))
            throw invalidEndpoint();
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException invalid) {
            throw invalidEndpoint();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!(scheme.equals("http") || scheme.equals("https"))
                || uri.isOpaque()
                || host == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !privateIpv4(host)) throw invalidEndpoint();
        int explicitPort = uri.getPort();
        int port = explicitPort == -1 ? (scheme.equals("http") ? 80 : 443) : explicitPort;
        if (port < 1
                || port > 65535
                || !uri.getRawAuthority().equals(host + (explicitPort == -1 ? "" : ":" + explicitPort)))
            throw invalidEndpoint();
        String path = uri.getRawPath();
        if (path == null) path = "";
        if (!path.equals(uri.normalize().getRawPath())
                || !path.chars()
                        .allMatch(value -> value >= 'a' && value <= 'z'
                                || value >= 'A' && value <= 'Z'
                                || value >= '0' && value <= '9'
                                || value == '/'
                                || value == '-'
                                || value == '_'
                                || value == '.'
                                || value == '~')) throw invalidEndpoint();
        return scheme + "://" + host + ":" + port + path.replaceAll("/+$", "");
    }

    private static boolean privateIpv4(String host) {
        String[] pieces = host.split("\\.", -1);
        if (pieces.length != 4) return false;
        int[] octets = new int[4];
        for (int index = 0; index < pieces.length; index++) {
            String piece = pieces[index];
            if (!piece.matches("[0-9]{1,3}")) return false;
            octets[index] = Integer.parseInt(piece);
            if (octets[index] > 255 || !piece.equals(String.valueOf(octets[index]))) return false;
        }
        return octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
    }

    private static IllegalArgumentException invalidEndpoint() {
        return new IllegalArgumentException("模型接口地址无效");
    }
}

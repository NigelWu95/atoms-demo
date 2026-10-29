package dev.atoms.demo.projectdelivery;

import dev.atoms.demo.common.ApiException;
import java.net.HttpCookie;
import java.util.*;

/** Only this project's namespaced, HttpOnly cookies cross the application boundary. */
final class PublicationCookies {
    private final String prefix;
    PublicationCookies(UUID project) { prefix = "ATOMS_APP_" + project.toString().replace("-", "") + "_"; }
    String incoming(List<String> headers) {
        if (headers.stream().mapToInt(String::length).sum() > 16384) throw invalid();
        var result = new ArrayList<String>(); var names = new HashSet<String>();
        for (String header : headers) for (String part : header.split(";", -1)) {
            int equals = part.indexOf('='); if (equals < 0) continue;
            String name = part.substring(0, equals).strip();
            if (!name.startsWith(prefix)) continue;
            name = name.substring(prefix.length()); String value = part.substring(equals + 1).strip();
            if (!name(name) || !value(value) || !names.add(name)) throw invalid();
            result.add(name + "=" + value);
        }
        String resultValue = String.join("; ", result);
        if (resultValue.length() > 8192) throw invalid();
        return result.isEmpty() ? null : resultValue;
    }
    List<String> outgoing(List<String> headers) {
        if (headers.size() > 32 || headers.stream().mapToInt(String::length).sum() > 8192) throw invalid();
        var result = new ArrayList<String>();
        for (String header : headers) {
            try {
                if (header.codePoints().anyMatch(c -> c < 32 || c > 126)) throw invalid();
                var parsed = HttpCookie.parse(header);
                if (parsed.size() != 1) throw invalid();
                var cookie = parsed.get(0);
                if (!name(cookie.getName()) || !value(cookie.getValue())) throw invalid();
                String age = cookie.getMaxAge() < 0 ? "" : "; Max-Age=" + Math.min(cookie.getMaxAge(), 604800);
                result.add(prefix + cookie.getName() + "=" + cookie.getValue() + age + "; Path=/; HttpOnly; SameSite=Strict");
            } catch (IllegalArgumentException failure) { throw invalid(); }
        }
        return List.copyOf(result);
    }
    private static boolean name(String value) { return value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"); }
    private static boolean value(String value) { return value != null && value.length() <= 4096 && value.matches("[\\x21\\x23-\\x2b\\x2d-\\x3a\\x3c-\\x5b\\x5d-\\x7e]*"); }
    private static ApiException invalid() { return new ApiException(422, "PUBLICATION_COOKIE_INVALID", "应用 Cookie 格式或大小不受支持。"); }
}

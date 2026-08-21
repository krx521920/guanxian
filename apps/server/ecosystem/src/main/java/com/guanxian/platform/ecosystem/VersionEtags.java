package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.PreconditionRequiredException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionEtags {
    private static final Pattern STRONG_ETAG = Pattern.compile("^\\\"([0-9]+)\\\"$");

    private VersionEtags() {
    }

    static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("If-Match header with the current strong ETag is required");
        }
        Matcher matcher = STRONG_ETAG.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new PreconditionRequiredException("If-Match must be a single strong version ETag such as \\"3\\"");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new PreconditionRequiredException("If-Match version is outside the supported range");
        }
    }

    static String format(long version) {
        return "\\"" + version + "\\"";
    }
}

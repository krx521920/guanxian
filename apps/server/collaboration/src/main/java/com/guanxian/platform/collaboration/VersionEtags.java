package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import org.springframework.http.HttpStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionEtags {
    private static final Pattern STRONG_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private VersionEtags() {
    }

    static String format(long version) {
        return "\"" + version + "\"";
    }

    static long requireVersion(String value) {
        if (value == null || value.isBlank()) {
            throw new PreconditionRequiredException("If-Match header is required");
        }
        Matcher matcher = STRONG_ETAG.matcher(value);
        if (!matcher.matches()) {
            throw new ApiException(
                    "INVALID_IF_MATCH",
                    "If-Match must be one strong version ETag",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new ApiException(
                    "INVALID_IF_MATCH",
                    "If-Match must be one strong version ETag",
                    HttpStatus.BAD_REQUEST);
        }
    }
}

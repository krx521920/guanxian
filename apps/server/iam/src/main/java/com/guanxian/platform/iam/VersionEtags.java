package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionEtags {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private VersionEtags() {
    }

    static long requiredVersion(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new PreconditionRequiredException("If-Match header is required");
        }
        if (values.size() != 1) {
            throw invalid();
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw invalid();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException("INVALID_IF_MATCH", "If-Match must be one strong version ETag", HttpStatus.BAD_REQUEST);
    }
}

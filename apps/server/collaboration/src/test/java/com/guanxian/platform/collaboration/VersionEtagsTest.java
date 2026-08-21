package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionEtagsTest {
    @Test
    void acceptsOnlyOneStrongNumericEtag() {
        assertEquals(12, VersionEtags.requireVersion("\"12\""));
        assertEquals("\"12\"", VersionEtags.format(12));
        assertThrows(PreconditionRequiredException.class, () -> VersionEtags.requireVersion(null));
        assertThrows(ApiException.class, () -> VersionEtags.requireVersion("W/\"12\""));
        assertThrows(ApiException.class, () -> VersionEtags.requireVersion("*"));
        assertThrows(ApiException.class, () -> VersionEtags.requireVersion("\"01\""));
    }
}

package com.guanxian.platform.notification;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationControllerTest {
    @Test
    void parsesOnlyOneStrongVersionEtag() {
        assertEquals(12, NotificationController.requiredVersion(List.of("\"12\"")));
        assertThrows(PreconditionRequiredException.class,
                () -> NotificationController.requiredVersion(null));
        assertThrows(ApiException.class,
                () -> NotificationController.requiredVersion(List.of("W/\"12\"")));
        assertThrows(ApiException.class,
                () -> NotificationController.requiredVersion(List.of("\"1\"", "\"2\"")));
    }
}

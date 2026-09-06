package com.guanxian.platform.iam;

import java.util.UUID;

/** No operation can read a user's password or grant realm administrator roles. */
interface EnterpriseIdentityProvider {
    boolean enabled();
    String ensureDisabledUser(String username, UUID operationId);
    void verifyUser(String subject, String username, UUID operationId);
    void setEnabled(String subject, boolean enabled);
    void temporaryPassword(String subject, String password);
    void logout(String subject);
}

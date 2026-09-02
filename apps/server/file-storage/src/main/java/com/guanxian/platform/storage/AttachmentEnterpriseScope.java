package com.guanxian.platform.storage;

import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.stereotype.Component;

import java.util.UUID;

@FunctionalInterface
public interface AttachmentEnterpriseScope {
    boolean contains(UUID associationId, UUID enterpriseId, ActorScope actor);
}

@Component
class MemberDirectoryAttachmentEnterpriseScope implements AttachmentEnterpriseScope {
    private final MemberDirectory members;

    MemberDirectoryAttachmentEnterpriseScope(MemberDirectory members) {
        this.members = members;
    }

    @Override
    public boolean contains(UUID associationId, UUID enterpriseId, ActorScope actor) {
        return associationId != null && enterpriseId != null
                && members.findById(enterpriseId, actor)
                .filter(member -> !member.deleted())
                .filter(member -> associationId.equals(member.associationId()))
                .isPresent();
    }
}

package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.member.repository", havingValue = "memory")
class InMemoryMemberRepository implements MemberRepository {
    private final ConcurrentMap<UUID, MemberProfile> members = new ConcurrentHashMap<>();
    private final UUID defaultAssociationId;

    InMemoryMemberRepository() {
        this(UUID.fromString("00000000-0000-0000-0000-000000000106"));
    }

    InMemoryMemberRepository(
            @Value("${guanxian.security.demo.association-id:00000000-0000-0000-0000-000000000106}")
            UUID defaultAssociationId) {
        this.defaultAssociationId = defaultAssociationId;
    }

    @Override
    public List<MemberProfile> findAll() {
        return List.copyOf(members.values());
    }

    @Override
    public Optional<MemberProfile> findById(UUID id) {
        return Optional.ofNullable(members.get(id));
    }

    @Override
    public UUID defaultAssociationId() {
        return defaultAssociationId;
    }

    @Override
    public void insert(MemberProfile member) {
        members.put(member.id(), member);
    }

    @Override
    public boolean update(MemberProfile member, long expectedVersion) {
        while (true) {
            MemberProfile current = members.get(member.id());
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            if (members.replace(member.id(), current, member)) {
                return true;
            }
        }
    }

    @Override
    public boolean deleteById(UUID id, long expectedVersion) {
        while (true) {
            MemberProfile current = members.get(id);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            if (members.remove(id, current)) {
                return true;
            }
        }
    }
}

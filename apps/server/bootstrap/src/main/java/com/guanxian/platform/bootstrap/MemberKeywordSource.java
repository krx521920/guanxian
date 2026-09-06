package com.guanxian.platform.bootstrap;

import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.tender.KeywordSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adapts the member module's public {@link MemberDirectory} to the tender module's
 * {@link KeywordSource} port, so the tender module can resolve enterprise keywords and
 * member identity without depending on the member module's implementation.
 */
@Component
public class MemberKeywordSource implements KeywordSource {
    private final MemberDirectory memberDirectory;

    public MemberKeywordSource(MemberDirectory memberDirectory) {
        this.memberDirectory = memberDirectory;
    }

    @Override
    public List<String> keywordsFor(UUID enterpriseId, ActorScope actor) {
        return memberDirectory.findById(enterpriseId, actor)
                .map(MemberKeywordSource::keywords)
                .orElse(List.of());
    }

    @Override
    public List<UUID> allEnterpriseIds(ActorScope actor) {
        return memberDirectory.findAll(null, actor).stream()
                .filter(member -> "ACTIVE".equals(member.status()))
                .map(MemberProfile::id)
                .toList();
    }

    @Override
    public String enterpriseName(UUID enterpriseId, ActorScope actor) {
        return memberDirectory.findById(enterpriseId, actor)
                .map(MemberProfile::name)
                .orElse(null);
    }

    private static List<String> keywords(MemberProfile member) {
        List<String> values = new ArrayList<>();
        if (member.category() != null && !member.category().isBlank()) {
            values.add(member.category().trim());
        }
        values.addAll(member.capabilities());
        values.addAll(member.products());
        return values.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }
}

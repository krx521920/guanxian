package com.guanxian.platform.tender;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TenderStore {
    List<TenderView> findAll(String query, String category, String region, String status, int offset, int limit);

    long countAll(String query, String category, String region, String status);

    Optional<TenderView> findById(UUID id);

    TenderView insert(UUID associationId, TenderUpsertRequest request, ActorScope actor);

    Optional<TenderView> update(UUID id, long expectedVersion, TenderUpsertRequest request, ActorScope actor);

    TenderPushView upsertPush(
            UUID tenderId, String tenderTitle, UUID enterpriseId, String enterpriseName, ActorScope actor);

    List<TenderPushView> findPushes(UUID tenderId);

    List<UUID> listPushedEnterpriseIds(UUID tenderId);

    List<TenderView> findRelevant(List<String> enterpriseKeywords, String region, int offset, int limit);

    long countRelevant(List<String> enterpriseKeywords, String region);
}

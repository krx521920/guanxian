package com.guanxian.platform.member.web;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberControllerPaginationTest {
    @Test
    void pageTotalAndItemsUseTheSameAlreadyAuthorizedSnapshot() {
        MemberService memberService = mock(MemberService.class);
        ActorScopeResolver actorScopeResolver = mock(ActorScopeResolver.class);
        Authentication authentication = mock(Authentication.class);
        ActorScope actor = new ActorScope(
                null, "partner", "partner", UUID.randomUUID(), null,
                Set.of("ENTERPRISE_MEMBER"), Set.of());
        MemberProfile visible = member();
        when(actorScopeResolver.resolve(authentication)).thenReturn(actor);
        when(memberService.findAll("管线", "ACTIVE", false, actor)).thenReturn(List.of(visible));
        MemberController controller = new MemberController(memberService, actorScopeResolver);

        ApiResponse<MemberPage> response = controller.page(
                "管线", "ACTIVE", false, 0, 20, authentication);

        assertEquals(1, response.data().total());
        assertEquals(List.of(visible.id()), response.data().items().stream()
                .map(MemberListItem::id)
                .toList());
        verify(memberService).findAll("管线", "ACTIVE", false, actor);
    }

    private static MemberProfile member() {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return new MemberProfile(
                UUID.fromString("73000000-0000-0000-0000-000000000101"),
                UUID.fromString("73000000-0000-0000-0000-000000000001"),
                "管线企业",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                "PARTNERS",
                "ACTIVE",
                0,
                now,
                now,
                null,
                null,
                null);
    }
}

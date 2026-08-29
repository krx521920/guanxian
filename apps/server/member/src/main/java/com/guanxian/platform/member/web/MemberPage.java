package com.guanxian.platform.member.web;

import java.util.List;

public record MemberPage(List<MemberListItem> items, long total, int page, int size) {
}

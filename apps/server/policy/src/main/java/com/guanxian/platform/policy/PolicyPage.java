package com.guanxian.platform.policy;

import java.util.List;

public record PolicyPage(List<PolicyView> items, long total, int page, int size) {
}

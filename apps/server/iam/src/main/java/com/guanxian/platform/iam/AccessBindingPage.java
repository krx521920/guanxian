package com.guanxian.platform.iam;

import java.util.List;

record AccessBindingPage(List<AccessBindingView> items, long total, int page, int size) {
}

package com.guanxian.platform.tender;

import java.util.List;

public record TenderPage(List<TenderView> items, int page, int size, long total) {
}

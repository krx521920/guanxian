package com.guanxian.platform.iam;

import java.util.List;

record CrossAssociationPage<T>(List<T> items, long total, int page, int size) {
}

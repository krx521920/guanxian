package com.guanxian.platform.tender;

import java.util.List;
import java.util.UUID;

public record TenderPushRequest(List<UUID> enterpriseIds) {
}

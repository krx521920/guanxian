package com.guanxian.platform.shared.notification;

import com.guanxian.platform.shared.security.ActorScope;

@FunctionalInterface
public interface BusinessNotificationPublisher {
    int publish(BusinessNotification notification, ActorScope actor);
}

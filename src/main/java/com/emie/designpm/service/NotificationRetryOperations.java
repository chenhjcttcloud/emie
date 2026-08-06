package com.emie.designpm.service;

import java.util.List;
import java.util.Map;

/** 通知投递的统一业务契约，核心连接池和后台连接池实现都必须遵守。 */
public interface NotificationRetryOperations {
    void retryDueDeliveries();
    List<Map<String, Object>> recentFailures();
    List<Map<String, Object>> recentFeishuDeliveries();
    void retryNow(Long deliveryId, String operatorUserId);
}

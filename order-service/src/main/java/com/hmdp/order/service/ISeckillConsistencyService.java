package com.hmdp.order.service;

import com.hmdp.dto.Result;

public interface ISeckillConsistencyService {

    Result checkOrderConsistency(Long orderId);

    Result syncStockFromRedisToDb(Long voucherId);

    Result checkPendingOrders();

    Result repairInconsistentData(Long voucherId);
}

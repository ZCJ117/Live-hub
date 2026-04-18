package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    private Long userId;

    private Long voucherId;

    private Long timestamp;

    private Integer retryCount = 0;

    public SeckillOrderMessage(Long orderId, Long userId, Long voucherId) {
        this.orderId = orderId;
        this.userId = userId;
        this.voucherId = voucherId;
        this.timestamp = System.currentTimeMillis();
        this.retryCount = 0;
    }
}

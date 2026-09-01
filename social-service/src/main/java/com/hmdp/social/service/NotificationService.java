package com.hmdp.social.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.social.entity.Notification;
import com.hmdp.social.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 站内信服务（写入：MQ 消费端；查询：我的站内信列表） */
@Service
@Slf4j
public class NotificationService extends ServiceImpl<NotificationMapper, Notification> {

    public void saveNotification(Long userId, Long relatedId, String title, String content) {
        Notification n = new Notification()
                .setUserId(userId)
                .setType("TICKET")
                .setTitle(title)
                .setContent(content)
                .setRelatedId(relatedId);
        save(n);
        log.info("站内信已落库: userId={}, relatedId={}", userId, relatedId);
    }

    public List<Notification> listByUser(Long userId) {
        return list(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .last("LIMIT 50"));
    }
}

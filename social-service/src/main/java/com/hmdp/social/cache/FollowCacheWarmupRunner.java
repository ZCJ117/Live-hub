package com.hmdp.social.cache;

import com.hmdp.entity.Follow;
import com.hmdp.social.mapper.FollowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关注关系的 Redis Set 启动回填（SPEC-09 §1.11）。
 *
 * <p>关注/取关是 DB + Redis 双写，存量库（或历史双写失败的库）中 {@code tb_follow} 有数据
 * 而 {@code follows:{userId}} 为空，会让 {@code followCommons} 的 SINTER 恒返回空列表。
 * 启动时把全量关注关系按 userId 分组批量 SADD 回填。
 *
 * <p>{@code SADD} 幂等，重复启动无害；整体 try/catch，**失败只记日志不阻塞启动**
 * （沿 SPEC-05 §5.2「预热不得阻塞启动」的既定范式）。
 */
@Slf4j
@Component
public class FollowCacheWarmupRunner implements ApplicationRunner {

    private static final String FOLLOW_KEY_PREFIX = "follows:";

    private final FollowMapper followMapper;

    private final StringRedisTemplate stringRedisTemplate;

    public FollowCacheWarmupRunner(FollowMapper followMapper, StringRedisTemplate stringRedisTemplate) {
        this.followMapper = followMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Follow> follows;
        try {
            follows = followMapper.selectList(null);
        } catch (Exception e) {
            log.error("关注关系回填：读取 tb_follow 失败，跳过回填", e);
            return;
        }
        if (follows == null || follows.isEmpty()) {
            log.info("关注关系回填：无数据，跳过");
            return;
        }

        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (Follow follow : follows) {
            if (follow.getUserId() == null || follow.getFollowUserId() == null) {
                continue;
            }
            grouped.computeIfAbsent(follow.getUserId(), k -> new ArrayList<>())
                    .add(follow.getFollowUserId().toString());
        }

        int ok = 0;
        for (Map.Entry<Long, List<String>> entry : grouped.entrySet()) {
            String key = FOLLOW_KEY_PREFIX + entry.getKey();
            try {
                stringRedisTemplate.opsForSet().add(key, entry.getValue().toArray(new String[0]));
                ok++;
            } catch (Exception e) {
                // 单个用户失败不影响其余用户，也不阻塞启动
                log.error("关注关系回填失败: key={}", key, e);
            }
        }
        log.info("关注关系回填完成：用户 {} / {}", ok, grouped.size());
    }
}

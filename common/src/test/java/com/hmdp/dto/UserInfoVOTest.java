package com.hmdp.dto;

import com.hmdp.entity.UserInfo;
import cn.hutool.core.bean.BeanUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UserInfoVOTest {

    @Test
    void 不含隐私字段() {
        Set<String> names = Arrays.stream(UserInfoVO.class.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());
        assertEquals(Set.of("userId", "city", "introduce", "fans", "followee", "level"), names);
    }

    @Test
    void 投影时隐私字段不会被带出() {
        UserInfo info = new UserInfo()
                .setUserId(7L).setCity("上海").setIntroduce("hi")
                .setFans(3).setFollowee(4).setLevel(true)
                .setCredits(999).setBirthday(LocalDate.of(1990, 1, 1)).setGender(Boolean.TRUE);

        UserInfoVO vo = BeanUtil.copyProperties(info, UserInfoVO.class);

        assertEquals(7L, vo.getUserId());
        assertEquals("上海", vo.getCity());
        assertEquals(3, vo.getFans());
        assertFalse(Arrays.stream(UserInfoVO.class.getDeclaredFields())
                .map(Field::getName).toList()
                .containsAll(List.of("credits", "birthday", "gender")));
    }
}

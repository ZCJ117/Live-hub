package com.hmdp.user.service;

import com.hmdp.dto.Result;
import com.hmdp.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplSendCodeTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @InjectMocks private UserServiceImpl userService;

    @Test
    void 手机号格式错误直接拒绝() {
        Result r = userService.sendCode("123", null, "127.0.0.1");
        assertFalse(r.getSuccess());
        assertEquals("手机格式错误", r.getErrorMsg());
    }

    @Test
    void 六十秒内重复发送被拒绝() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("login:code:limit:phone:13800138000"), anyString(),
                anyLong(), any(TimeUnit.class))).thenReturn(false);

        Result r = userService.sendCode("13800138000", null, "127.0.0.1");

        assertFalse(r.getSuccess());
        assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
    }

    @Test
    void 手机号24小时超过10次被拒绝() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.increment("login:code:count:phone:13800138000")).thenReturn(11L);

        Result r = userService.sendCode("13800138000", null, "127.0.0.1");

        assertFalse(r.getSuccess());
        assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
    }

    @Test
    void IP二十四小时超过二十次被拒绝() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.increment("login:code:count:phone:13800138000")).thenReturn(1L);
        when(valueOperations.increment("login:code:count:ip:127.0.0.1")).thenReturn(21L);

        Result r = userService.sendCode("13800138000", null, "127.0.0.1");

        assertFalse(r.getSuccess());
        assertEquals("验证码发送过于频繁，请稍后再试", r.getErrorMsg());
    }

    @Test
    void 正常发送写入验证码并返回成功() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        Result r = userService.sendCode("13800138000", null, "127.0.0.1");

        assertTrue(r.getSuccess());
        verify(valueOperations).set(eq("login:code:13800138000"), anyString(), eq(2L), eq(TimeUnit.MINUTES));
    }
}

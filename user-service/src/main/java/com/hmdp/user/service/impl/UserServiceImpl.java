package com.hmdp.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.user.mapper.UserMapper;
import com.hmdp.user.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 左常健
 * @since 2025-12-7
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /** 验证码频控口径（SPEC-06 §5.5） */
    private static final long CODE_PHONE_INTERVAL_SECONDS = 60L;
    private static final long CODE_PHONE_MAX_PER_DAY = 10L;
    private static final long CODE_IP_MAX_PER_DAY = 20L;
    private static final long CODE_COUNT_TTL_HOURS = 24L;

    //NOTE 发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session, String clientIp) {

        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机格式错误");
        }

        // 频控（SPEC-06 §5.5）：未被频控前，该接口可对任意手机号无限轰炸
        if (!checkSendCodeLimit(phone, clientIp)) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }

        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码。注意：**不得**在此打印验证码明文——
        // user-service 的 logging.level.com.hmdp 为 debug，明文会直接落到日志（SPEC-06 §5.5）
        log.debug("发送短信验证码成功，phone={}", phone);
        //返回ok
        return Result.ok();
    }

    /**
     * 验证码发送频控：手机号 60 秒 1 次 + 24 小时 10 次；IP 24 小时 20 次。
     * Redis 异常时放行（不因限流组件故障阻断登录），仅在计数超限时拒绝。
     */
    private boolean checkSendCodeLimit(String phone, String clientIp) {
        try {
            Boolean allowed = stringRedisTemplate.opsForValue().setIfAbsent(
                    LOGIN_CODE_LIMIT_KEY + phone, "1", CODE_PHONE_INTERVAL_SECONDS, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(allowed)) {
                return false;
            }
            if (incrWithTtl(LOGIN_CODE_COUNT_KEY + phone) > CODE_PHONE_MAX_PER_DAY) {
                return false;
            }
            if (clientIp != null && !clientIp.isBlank()
                    && incrWithTtl(LOGIN_CODE_IP_COUNT_KEY + clientIp) > CODE_IP_MAX_PER_DAY) {
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("验证码频控检查异常，放行: phone={}", phone, e);
            return true;
        }
    }

    private long incrWithTtl(String key) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, CODE_COUNT_TTL_HOURS, TimeUnit.HOURS);
        }
        return count == null ? 0L : count;
    }


    //NOTE 登录功能实现
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2如果不符合，报错
            return Result.fail("手机号格式错误");
        }
        //3 验证码或密码校验
        String code = loginForm.getCode();
        String password = loginForm.getPassword();
        if (code != null && !code.isEmpty()) {
            // 验证码登录
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
            if(cacheCode == null||!cacheCode.equals(code)){
                //不一致，报错
                return Result.fail("验证码错误");
            }
            // 验证码正确，删除Redis中的验证码，确保一次失效
            log.debug("验证码验证成功，删除验证码，phone: {}", phone);
            stringRedisTemplate.delete(LOGIN_CODE_KEY+phone);
            log.debug("验证码删除完成，phone: {}", phone);
        } else if (password != null && !password.isEmpty()) {
            // 密码登录（待实现）
            return Result.fail("密码登录功能暂未实现");
        } else {
            return Result.fail("请输入验证码或密码");
        }
        //4一致，根据手机号查用户
        User user = query().eq("phone", phone).one();

        //5判断用户是否存在
        if(user == null){
            //6不存在，创建并保存
            user = createUserWithPhone(phone);
        }

        //7 使用 Sa-Token 登录
        //7.1 登录
        StpUtil.login(user.getId());

        //7.2 将 User 转为 UserDTO 存入会话
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        StpUtil.getSession().set("user", userDTO);

        //7.3 返回 token
        return Result.ok(StpUtil.getTokenValue());
    }



    private User createUserWithPhone(String phone){
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }


}
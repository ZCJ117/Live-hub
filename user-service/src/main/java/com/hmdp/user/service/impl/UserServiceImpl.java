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


    //NOTE 发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {

        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合，返回错误信息
            return Result.fail("手机格式错误");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);


        //保存验证码到redis中
//        session.setAttribute("code",code);   NOTE 这里用redis代替session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);


        // 发送验证码
        log.debug("发送短信验证码成功，验证码:{}",code);
        //返回ok
        return Result.ok();
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

    private UserDTO convertToDTO(User user) {
        UserDTO userDTO = new UserDTO();
        org.springframework.beans.BeanUtils.copyProperties(user, userDTO);
        return userDTO;
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
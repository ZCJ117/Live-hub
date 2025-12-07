package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import com.hmdp.utils.RedisConstants;
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
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);


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
        //3 从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cacheCode == null||!cacheCode.equals(code)){
            //不一致，报错
            return Result.fail("验证码错误");
        }
        //4一致，根据手机号查用户
        User user = query().eq("phone", phone).one();

        //5判断用户是否存在
        if(user == null){
            //6不存在，创建并保存
            user = createUserWithPhone(phone);
        }

        //7保存用户信息到redis中
        //7.1 随机生成token 作为登录令牌
        //NOTE 使用UUID生成一个唯一的字符串作为token
        String token = UUID.randomUUID().toString(true);

        //7.2将User转为hashMap存储，并确保所有值为String类型
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue != null) {
                                return fieldValue.toString();
                            }
                            return null;
                        }));

        //7.3储存
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //7.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
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


//NOTE 这段代码展示了如何使用 Spring Boot 和 MyBatis-Plus 实现用户登录和签到功能，
// 并结合 Redis 提高性能，适合用于面试中展示对后端开发技术栈的掌握。

//NOTE 高频面试题
//NOTE 1.为什么用Redis替代session？
//答：使用Redis替代session有以下几个优势：
// 分布式支持：Redis作为分布式缓存，可以轻松支持多台服务器部署的应用，解决了传统session在分布式环境下共享的问题。
// 性能提升：Redis的读写速度非常快，能够显著提升用户登录验证和会话管理的性能，减少数据库的压力。
// 可扩展性：Redis支持水平扩展，可以根据业务增长灵活调整资源，满足高并发需求。
// 数据持久化：Redis提供多种持久化机制，确保用户会话数据的安全性和可靠性。
// 灵活的数据结构：Redis支持多种数据结构（如字符串、哈希、列表等），使得存储和管理用户会话数据更加灵活和高效。

//NOTE 2.如何保证用户登录的安全性？
//答：保证用户登录安全性可以采取以下措施：
// 强密码策略：要求用户设置强密码，包含大小写字母、数字和特殊字符，定期更新密码。
// 多因素认证（MFA）：在登录过程中引入多因素认证，如短信验证码、邮箱验证或使用认证应用程序。
// HTTPS加密：确保所有登录请求通过HTTPS传输，防止数据在传输过程中被窃取。
// 会话管理：使用安全的会话管理机制，设置合理的会话过期时间，防止会话劫持。
// 登录监控：监控异常登录行为，如频繁登录失败、异地登录等，及时采取措施。
// 数据加密：对敏感用户数据进行加密存储，防止数据泄露。
// 安全审计：定期审计登录日志，发现并处理潜在的安全威胁。

//NOTE 3.分布式环境下登录态同步问题
//答：在分布式环境下，可以通过以下方式实现登录态同步：
// 使用集中式存储：将登录态信息存储在集中式的Redis缓存中，所有应用实例都可以访问同一份登录态数据。
// 令牌机制：使用JWT（JSON Web Token）等令牌机制，用户登录后生成令牌，客户端携带令牌进行请求，服务器验证令牌有效性。
// 负载均衡：使用负载均衡器，将用户请求分发到不同的应用实例，确保每次请求都能访问到正确的登录态信息。
// 会话复制：在应用服务器之间复制会话数据，确保用户在不同服务器上访问时能够保持登录状态。
// 定期同步：定期将登录态信息从一个实例同步到其他实例，确保数据一致性。
// 心跳机制：通过心跳机制检测各个实例的状态，确保登录态信息的及时更新和同步.

package com.hmdp.social.feign;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 用户服务Feign客户端
 */
@FeignClient(name = "user-service")
public interface UserFeignClient {

    /**
     * 根据用户id查询用户信息
     */
    @GetMapping("/user/{id}")
    Result getUserById(@PathVariable("id") Long id);

    /**
     * 根据用户id列表查询用户信息
     */
    @GetMapping("/user/list")
    Result getUserByIds(@RequestBody List<Long> ids);
}

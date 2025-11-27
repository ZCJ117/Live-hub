package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;

///  这个是因为LoginIntercptor类中，要创建一个DTO对象然后进行属性拷贝，不直接进行强制转换，所创建的一个工具类

public class UserConvert {

    private UserConvert() {
        // 工具类，防止实例化
    }

    public static UserDTO toDTO(User user) {
        if (user == null) {
            return null;
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return userDTO;
    }

    // 如果需要反向转换，可以添加这个方法
    public static User toEntity(UserDTO userDTO) {
        if (userDTO == null) {
            return null;
        }
        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        return user;
    }
}
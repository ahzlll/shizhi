package com.ahz.shizhi.auth.dto;

import com.ahz.shizhi.user.User;

/**
 * 对外的用户信息。不含密码哈希、不含内部状态位。
 */
public record UserProfile(long id, String username, String email, String nickname) {

    public static UserProfile from(User user) {
        return new UserProfile(user.id(), user.username(), user.email(), user.nickname());
    }
}

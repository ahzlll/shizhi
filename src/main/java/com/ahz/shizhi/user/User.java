package com.ahz.shizhi.user;

/**
 * 用户。只放认证需要的字段，密码哈希不外泄到这个对象里。
 */
public record User(long id, String username, String email, String nickname, int status) {

    public static final int STATUS_ACTIVE = 1;

    public boolean isActive() {
        return status == STATUS_ACTIVE;
    }
}

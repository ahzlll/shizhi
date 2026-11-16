package com.ahz.shizhi.model;

/**
 * 送给模型的一条对话消息。
 *
 * @param role    {@code system} / {@code user} / {@code assistant}，与 OpenAI 兼容接口一致
 * @param content 消息正文
 */
public record Turn(String role, String content) {

    public static Turn system(String content) {
        return new Turn("system", content);
    }

    public static Turn user(String content) {
        return new Turn("user", content);
    }

    public static Turn assistant(String content) {
        return new Turn("assistant", content);
    }
}

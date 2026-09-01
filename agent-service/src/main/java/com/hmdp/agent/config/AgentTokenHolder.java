package com.hmdp.agent.config;

/**
 * 当前用户 token 持有者（跨线程池透传给 Feign，供下游 Sa-Token 识别登录态）
 * 在请求线程 set、异步任务内读取、finally 中 clear
 */
public final class AgentTokenHolder {

    private static final ThreadLocal<String> TL = new ThreadLocal<>();

    private AgentTokenHolder() {
    }

    public static void set(String token) {
        if (token == null) {
            clear();
        } else {
            TL.set(token);
        }
    }

    public static String get() {
        return TL.get();
    }

    public static void clear() {
        TL.remove();
    }
}

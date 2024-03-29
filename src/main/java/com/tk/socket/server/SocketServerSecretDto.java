package com.tk.socket.server;

import com.tk.socket.entity.SocketSecret;
import io.netty.buffer.ByteBuf;

import java.io.Serializable;

/**
 * socket密钥dto
 */
public class SocketServerSecretDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * appKey
     */
    private final String appKey;
    /**
     * 加密类
     */
    private final SocketSecret secret;
    /**
     * 最大连接数
     */
    private final Integer maxConnection;
    /**
     * 心跳间隔时间，默认60（单位：秒）
     */
    private final Integer heartbeatInterval;
    /**
     * 心跳超时时间，默认180（单位：秒）
     */
    private final Integer heartbeatTimeout;

    public String getAppKey() {
        return appKey;
    }

    public SocketSecret getSecret() {
        return secret;
    }

    public Integer getMaxConnection() {
        return maxConnection;
    }

    public Integer getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public Integer getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public ByteBuf encode(ByteBuf data) {
        return secret.encode(data);
    }

    public ByteBuf decode(ByteBuf data) {
        return secret.decode(data);
    }

    public SocketServerSecretDto(String appKey, SocketSecret secret, Integer maxConnection, Integer heartbeatInterval, Integer heartbeatTimeout) {
        this.appKey = appKey;
        this.secret = secret;
        this.maxConnection = maxConnection;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public static SocketServerSecretDto build(String appKey, SocketSecret secret, Integer maxConnection, Integer heartbeatInterval, Integer heartbeatTimeout) {
        return new SocketServerSecretDto(appKey, secret, maxConnection, heartbeatInterval, heartbeatTimeout);
    }
}
package com.tk.socket.server;

import com.tk.socket.SocketMsgDecode;
import com.tk.socket.SocketMsgEncode;

import java.io.Serializable;

/**
 * socket密钥dto
 */
public class SocketSecretDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * appKey
     */
    private final String appKey;
    /**
     * 密文
     */
    private final byte[] secret;
    /**
     * 加密方法
     */
    private final SocketMsgEncode msgEncode;
    /**
     * 解密方法
     */
    private final SocketMsgDecode msgDecode;
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
    /**
     * 加密方式
     */
    private final String signMethod;

    public String getAppKey() {
        return appKey;
    }

    public byte[] getSecret() {
        return secret;
    }

    public SocketMsgEncode getMsgEncode() {
        return msgEncode;
    }

    public SocketMsgDecode getMsgDecode() {
        return msgDecode;
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

    public String getSignMethod() {
        return signMethod;
    }

    public byte[] encode(byte[] data) {
        return msgEncode.encode(data, secret);
    }

    public byte[] decode(byte[] data) {
        return msgDecode.decode(data, secret);
    }

    public SocketSecretDto(String appKey, byte[] secret, SocketMsgEncode msgEncode, SocketMsgDecode msgDecode, Integer maxConnection, Integer heartbeatInterval, Integer heartbeatTimeout, String signMethod) {
        this.appKey = appKey;
        this.secret = secret;
        if (msgEncode == null) {
            msgEncode = (data, secretBytes) -> data;
        }
        this.msgEncode = msgEncode;
        if (msgDecode == null) {
            msgDecode = (data, secretBytes) -> data;
        }
        this.msgDecode = msgDecode;
        this.maxConnection = maxConnection;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatTimeout = heartbeatTimeout;
        this.signMethod = signMethod;
    }

    public static SocketSecretDto build(String appKey, byte[] secret, SocketMsgEncode msgEncode, SocketMsgDecode msgDecode, Integer maxConnection, Integer heartbeatInterval, Integer heartbeatTimeout, String signMethod) {
        return new SocketSecretDto(appKey, secret, msgEncode, msgDecode, maxConnection, heartbeatInterval, heartbeatTimeout, signMethod);
    }
}
package com.tk.socket.client;

import com.tk.socket.SocketMsgDecode;
import com.tk.socket.SocketMsgEncode;
import io.netty.channel.Channel;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.Serializable;
import java.time.Duration;

import static org.apache.commons.pool2.impl.BaseObjectPoolConfig.DEFAULT_MAX_WAIT;
import static org.apache.commons.pool2.impl.GenericObjectPoolConfig.*;

public class SocketClientConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String host;

    private Integer port;

    private Integer msgSizeLimit;

    /**
     * appKey
     */
    private String appKey;
    /**
     * 密文
     */
    private byte[] secret;

    /**
     * 加密方法
     */
    private SocketMsgEncode msgEncode;

    /**
     * 解密方法
     */
    private SocketMsgDecode msgDecode;

    private int bossLoopThreadCount;

    private int maxHandlerDataThreadCount;

    private int singleThreadDataConsumerCount;

    private Duration poolMaxWait = DEFAULT_MAX_WAIT;

    private int poolMaxTotal = DEFAULT_MAX_TOTAL;

    private int poolMaxIdle = DEFAULT_MAX_IDLE;

    private int poolMinIdle = DEFAULT_MIN_IDLE;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getMsgSizeLimit() {
        return msgSizeLimit;
    }

    public void setMsgSizeLimit(Integer msgSizeLimit) {
        this.msgSizeLimit = msgSizeLimit;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public byte[] getSecret() {
        return secret;
    }

    public void setSecret(byte[] secret) {
        this.secret = secret;
    }

    public SocketMsgEncode getMsgEncode() {
        if (msgEncode == null) {
            msgEncode = (data, secret) -> data;
        }
        return msgEncode;
    }

    public void setMsgEncode(SocketMsgEncode msgEncode) {
        if (msgEncode == null) {
            msgEncode = (data, secret) -> data;
        }
        this.msgEncode = msgEncode;
    }

    public SocketMsgDecode getMsgDecode() {
        if (msgDecode == null) {
            msgDecode = (data, secret) -> data;
        }
        return msgDecode;
    }

    public void setMsgDecode(SocketMsgDecode msgDecode) {
        if (msgDecode == null) {
            msgDecode = (data, secret) -> data;
        }
        this.msgDecode = msgDecode;
    }

    public int getBossLoopThreadCount() {
        return bossLoopThreadCount;
    }

    public void setBossLoopThreadCount(int bossLoopThreadCount) {
        this.bossLoopThreadCount = bossLoopThreadCount;
    }

    public int getMaxHandlerDataThreadCount() {
        return maxHandlerDataThreadCount;
    }

    public void setMaxHandlerDataThreadCount(int maxHandlerDataThreadCount) {
        this.maxHandlerDataThreadCount = maxHandlerDataThreadCount;
    }

    public int getSingleThreadDataConsumerCount() {
        return singleThreadDataConsumerCount;
    }

    public void setSingleThreadDataConsumerCount(int singleThreadDataConsumerCount) {
        this.singleThreadDataConsumerCount = singleThreadDataConsumerCount;
    }

    public Duration getPoolMaxWait() {
        return poolMaxWait;
    }

    public void setPoolMaxWait(Duration poolMaxWait) {
        this.poolMaxWait = poolMaxWait;
    }

    public int getPoolMaxTotal() {
        return poolMaxTotal;
    }

    public void setPoolMaxTotal(int poolMaxTotal) {
        this.poolMaxTotal = poolMaxTotal;
    }

    public int getPoolMaxIdle() {
        return poolMaxIdle;
    }

    public void setPoolMaxIdle(int poolMaxIdle) {
        this.poolMaxIdle = poolMaxIdle;
    }

    public int getPoolMinIdle() {
        return poolMinIdle;
    }

    public void setPoolMinIdle(int poolMinIdle) {
        this.poolMinIdle = poolMinIdle;
    }

    public GenericObjectPoolConfig<Channel> getPoolConfig() {
        GenericObjectPoolConfig<Channel> config = new GenericObjectPoolConfig<>();
        config.setMaxWait(poolMaxWait);
        config.setMaxTotal(poolMaxTotal);
        config.setMaxIdle(poolMaxIdle);
        config.setMinIdle(poolMinIdle);
        config.setTestOnBorrow(true);
        return config;
    }

}

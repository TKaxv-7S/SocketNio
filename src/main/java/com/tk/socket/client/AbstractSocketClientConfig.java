package com.tk.socket.client;

import io.netty.channel.Channel;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.Serializable;
import java.time.Duration;

import static org.apache.commons.pool2.impl.BaseObjectPoolConfig.DEFAULT_MAX_WAIT;
import static org.apache.commons.pool2.impl.GenericObjectPoolConfig.*;

public abstract class AbstractSocketClientConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String host;

    private Integer port;

    private Integer msgSizeLimit;

    private int bossLoopThreadCount;

    private int maxHandlerDataThreadCount;

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

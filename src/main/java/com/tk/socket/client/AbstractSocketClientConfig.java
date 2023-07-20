package com.tk.socket.client;

import io.netty.channel.Channel;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.Serializable;
import java.time.Duration;

import static org.apache.commons.pool2.impl.GenericObjectPoolConfig.*;

public abstract class AbstractSocketClientConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(60);

    private String host;

    private Integer port;

    private Integer msgSizeLimit;

    private int bossLoopThreadCount;

    private int maxHandlerDataThreadCount;

    private Integer heartbeatInterval;

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

    public Integer getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Integer heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getPoolMaxWait() {
        return poolMaxWait;
    }

    public void setPoolMaxWait(Duration poolMaxWait) {
        this.poolMaxWait = checkPoolMaxWait(poolMaxWait);
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

    private Duration checkPoolMaxWait(Duration duration) {
        return Duration.ZERO.compareTo(duration) > 0 ? Duration.ofSeconds(10) : duration.compareTo(DEFAULT_MAX_WAIT) > 0 ? DEFAULT_MAX_WAIT : duration;
    }

    public GenericObjectPoolConfig<Channel> getPoolConfig() {
        GenericObjectPoolConfig<Channel> config = new GenericObjectPoolConfig<>();
        config.setMaxWait(checkPoolMaxWait(poolMaxWait));
        config.setMaxTotal(poolMaxTotal);
        config.setMaxIdle(poolMaxIdle);
        config.setMinIdle(poolMinIdle);
        config.setTestOnCreate(true);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        //config.setLifo(false);
        return config;
    }

}

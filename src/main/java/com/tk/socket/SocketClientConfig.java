package com.tk.socket;

import io.netty.channel.Channel;
import lombok.Data;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.Serializable;
import java.time.Duration;

import static org.apache.commons.pool2.impl.BaseObjectPoolConfig.DEFAULT_MAX_WAIT;
import static org.apache.commons.pool2.impl.GenericObjectPoolConfig.*;

@Data
public class SocketClientConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String host;

    private Integer port;

    private Integer msgSizeLimit;

    private int maxHandlerDataThreadCount;

    private int singleThreadDataConsumerCount;

    private Duration poolMaxWait = DEFAULT_MAX_WAIT;

    private int poolMaxTotal = DEFAULT_MAX_TOTAL;

    private int poolMaxIdle = DEFAULT_MAX_IDLE;

    private int poolMinIdle = DEFAULT_MIN_IDLE;

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

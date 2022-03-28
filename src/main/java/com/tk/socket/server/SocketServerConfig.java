package com.tk.socket.server;

import java.io.Serializable;

public class SocketServerConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer port;

    private Integer msgSizeLimit;

    private int bossLoopThreadCount;

    private int eventLoopThreadCount;

    private int maxHandlerDataThreadCount;

    private int singleThreadDataConsumerCount;

    private Long unknownWaitMsgTimeoutSeconds;

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

    public int getEventLoopThreadCount() {
        return eventLoopThreadCount;
    }

    public void setEventLoopThreadCount(int eventLoopThreadCount) {
        this.eventLoopThreadCount = eventLoopThreadCount;
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

    public Long getUnknownWaitMsgTimeoutSeconds() {
        if (unknownWaitMsgTimeoutSeconds == null) {
            unknownWaitMsgTimeoutSeconds = 5L;
        }
        return unknownWaitMsgTimeoutSeconds;
    }

    public void setUnknownWaitMsgTimeoutSeconds(Long unknownWaitMsgTimeoutSeconds) {
        if (unknownWaitMsgTimeoutSeconds == null) {
            unknownWaitMsgTimeoutSeconds = 5L;
        }
        this.unknownWaitMsgTimeoutSeconds = unknownWaitMsgTimeoutSeconds;
    }
}

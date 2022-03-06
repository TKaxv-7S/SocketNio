package com.tk.socket;

import lombok.Data;

import java.io.Serializable;

@Data
public class SocketServerConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer port;

    private Integer msgSizeLimit;

    private int eventLoopThreadCount;

    private int maxHandlerDataThreadCount;

    private int singleThreadDataConsumerCount;

}

package com.tk.socket;

import com.tk.socket.server.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultSocketNioServer extends SocketNioServer<SocketClientCache<SocketSecretDto>> {

    public DefaultSocketNioServer(SocketServerConfig config, SocketServerHandler socketServerHandler, SocketClientCache<SocketSecretDto> socketClientCache) {
        super(config, socketServerHandler, socketClientCache);
    }

    public DefaultSocketNioServer(SocketServerConfig config, SocketServerHandler socketServerHandler) {
        super(config, socketServerHandler, new DefaultSocketClientCache());
    }

}

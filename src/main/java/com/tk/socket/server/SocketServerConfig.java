package com.tk.socket.server;

public class SocketServerConfig extends AbstractSocketServerConfig {

    private SocketServerHandler socketServerHandler;

    private SocketClientCache<? extends SocketSecretDto> socketClientCache;

    public SocketServerHandler getSocketServerHandler() {
        return socketServerHandler;
    }

    public void setSocketServerHandler(SocketServerHandler socketServerHandler) {
        this.socketServerHandler = socketServerHandler;
    }

    public SocketClientCache<? extends SocketSecretDto> getSocketClientCache() {
        return socketClientCache;
    }

    public void setSocketClientCache(SocketClientCache<? extends SocketSecretDto> socketClientCache) {
        this.socketClientCache = socketClientCache;
    }
}

package com.tk.socket.server;

public class SocketServerChannelQueue extends SocketServerBaseChannelQueue<SocketServerChannel> {
    public SocketServerChannelQueue() {
        super();
    }

    public SocketServerChannelQueue(int maxSize) {
        super(maxSize);
    }

    public SocketServerChannelQueue(int maxSize, int timeoutSeconds) {
        super(maxSize, timeoutSeconds);
    }

    public SocketServerChannelQueue(SocketServerChannel socketServerChannel) {
        super(socketServerChannel);
    }

    public SocketServerChannelQueue(SocketServerChannel socketServerChannel, int maxSize) {
        super(socketServerChannel, maxSize);
    }

    public SocketServerChannelQueue(SocketServerChannel socketServerChannel, int maxSize, int timeoutSeconds) {
        super(socketServerChannel, maxSize, timeoutSeconds);
    }
}

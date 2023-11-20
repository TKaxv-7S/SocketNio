package com.tk.socket.server;

import io.netty.channel.Channel;

public interface SocketNioServerWrite {

    void write(Channel socketChannel, Object data);

}

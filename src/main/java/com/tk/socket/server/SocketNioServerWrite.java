package com.tk.socket.server;

import com.tk.socket.SocketMsgDataDto;
import io.netty.channel.Channel;

public interface SocketNioServerWrite {

    void write(SocketMsgDataDto data, Channel channel);

    boolean writeAck(SocketMsgDataDto data, Channel channel);

    boolean writeAck(SocketMsgDataDto data, Channel channel, int seconds);

    SocketMsgDataDto writeSync(SocketMsgDataDto data, Channel channel);

    SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds, Channel channel);

}

package com.tk.socket.server;

import com.tk.socket.SocketMsgDataDto;

public interface SocketServerHandler {

    SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, SocketServerChannel serverChannel);

}

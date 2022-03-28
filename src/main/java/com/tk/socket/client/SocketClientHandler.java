package com.tk.socket.client;

import com.tk.socket.SocketMsgDataDto;

public interface SocketClientHandler {

    SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, SocketNioClient socketNioClient);

}

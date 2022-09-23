package com.tk.socket;

import com.tk.socket.client.SocketClientConfig;
import com.tk.socket.client.SocketClientHandler;
import com.tk.socket.client.SocketNioClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultSocketNioClient extends SocketNioClient {

    public DefaultSocketNioClient(SocketClientConfig config, SocketClientHandler socketClientHandler) {
        super(config, socketClientHandler);
    }

}

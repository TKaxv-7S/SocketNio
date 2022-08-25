package com.tk.socket.client;

import com.tk.socket.entity.SocketSecret;

public class SocketClientConfig extends AbstractSocketClientConfig {

    /**
     * appKey
     */
    private String appKey;

    /**
     * 加密类
     */
    private SocketSecret secret;

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public SocketSecret getSecret() {
        return secret;
    }

    public void setSecret(SocketSecret secret) {
        this.secret = secret;
    }
}

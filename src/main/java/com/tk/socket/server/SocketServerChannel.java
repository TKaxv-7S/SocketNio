package com.tk.socket.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;

public class SocketServerChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    //客户端appKey属性key
    private static final AttributeKey<String> APP_CLIENT_ATTR = AttributeKey.valueOf("appKey");

    /**
     * clientKey
     */
    private final String clientKey;

    /**
     * channelId
     */
    private final ChannelId channelId;

    /**
     * channel
     */
    private final Channel channel;

    private final AbstractSocketNioServer server;

    public String getClientKey() {
        return clientKey;
    }

    public ChannelId getChannelId() {
        return channelId;
    }

    public Channel getChannel() {
        return channel;
    }

    public AbstractSocketNioServer getServer() {
        return server;
    }

    public SocketServerChannel(String clientKey, Channel channel, AbstractSocketNioServer server) {
        this.clientKey = StringUtils.isNotBlank(clientKey) ? clientKey : null;
        this.channelId = channel.id();
        this.channel = channel;
        this.server = server;
        channel.attr(APP_CLIENT_ATTR).set(clientKey);
    }

    public static SocketServerChannel build(String clientKey, Channel channel, AbstractSocketNioServer server) {
        return new SocketServerChannel(clientKey, channel, server);
    }

    public void write(Object data) {
        server.write(channel, data);
    }

    public <T> void setAttr(String key, T value) {
        setAttr(AttributeKey.valueOf(key), value);
    }

    public <T> T getAttr(String key) {
        return getAttr(AttributeKey.valueOf(key));
    }

    public <T> void setAttr(AttributeKey<T> attrKey, T value) {
        Attribute<T> attr = channel.attr(attrKey);
        attr.set(value);
    }

    public <T> T getAttr(AttributeKey<T> attrKey) {
        Attribute<T> attr = channel.attr(attrKey);
        return attr.get();
    }

    public String getAppKey() {
        return getAppKey(clientKey);
    }

    public Boolean hasClientKey() {
        return StringUtils.isNotBlank(clientKey);
    }

    public Boolean hasAppKey() {
        return StringUtils.isNotBlank(clientKey);
    }

    public static String getClientKey(Channel channel) {
        return channel.attr(APP_CLIENT_ATTR).get();
    }

    public static String getAppKey(String clientKey) {
        if (StringUtils.isNotBlank(clientKey)) {
            String appKey;
            int length = clientKey.length();
            if (length < 1) {
                return null;
            }
            int endIndex = clientKey.indexOf("]", length - 1);
            if (endIndex > -1) {
                int startIndex = clientKey.indexOf("[");
                if (startIndex > -1) {
                    appKey = clientKey.substring(0, startIndex);
                } else {
                    appKey = clientKey;
                }
            } else {
                appKey = clientKey;
            }
            return appKey;
        }
        return null;
    }

    public static String getAppKey(Channel channel) {
        String clientKey = getClientKey(channel);
        return getAppKey(clientKey);
    }

    public static Boolean hasClientKey(Channel channel) {
        return channel.hasAttr(APP_CLIENT_ATTR);
    }

    public static Boolean hasAppKey(Channel channel) {
        return channel.hasAttr(APP_CLIENT_ATTR);
    }

}

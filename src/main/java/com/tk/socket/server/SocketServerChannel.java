package com.tk.socket.server;

import com.tk.socket.SocketMsgDataDto;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

import java.io.Serializable;

public class SocketServerChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * channelId
     */
    private ChannelId channelId;

    /**
     * channel
     */
    private Channel channel;

    private SocketNioServer socketNioServer;

    public ChannelId getChannelId() {
        return channelId;
    }

    public Channel getChannel() {
        return channel;
    }

    public SocketServerChannel() {
    }

    public SocketServerChannel(Channel channel, SocketNioServer socketNioServer) {
        this.channelId = channel.id();
        this.channel = channel;
        this.socketNioServer = socketNioServer;
    }

    public static SocketServerChannel build(Channel channel, SocketNioServer socketNioServer) {
        return new SocketServerChannel(channel, socketNioServer);
    }

    public void write(SocketMsgDataDto data) {
        socketNioServer.write(data, channel);
    }

    public boolean writeAck(SocketMsgDataDto data) {
        return socketNioServer.writeAck(data, channel);
    }

    public boolean writeAck(SocketMsgDataDto data, int seconds) {
        return socketNioServer.writeAck(data, channel, seconds);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data) {
        return socketNioServer.writeSync(data, 10, channel);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds) {
        return socketNioServer.writeSync(data, seconds, channel);
    }

    public SocketClientCache<? extends SocketSecretDto> getSocketClientCache() {
        return socketNioServer.getSocketClientCache();
    }
}

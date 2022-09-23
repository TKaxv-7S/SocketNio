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

    private SocketNioServerWrite socketNioServerWrite;

    public ChannelId getChannelId() {
        return channelId;
    }

    public Channel getChannel() {
        return channel;
    }

    public SocketServerChannel() {
    }

    public SocketServerChannel(Channel channel, SocketNioServerWrite socketNioServerWrite) {
        this.channelId = channel.id();
        this.channel = channel;
        this.socketNioServerWrite = socketNioServerWrite;
    }

    public static SocketServerChannel build(Channel channel, SocketNioServerWrite socketNioServerWrite) {
        return new SocketServerChannel(channel, socketNioServerWrite);
    }

    public void write(SocketMsgDataDto data) {
        socketNioServerWrite.write(data, channel);
    }

    public boolean writeAck(SocketMsgDataDto data) {
        return socketNioServerWrite.writeAck(data, channel);
    }

    public boolean writeAck(SocketMsgDataDto data, int seconds) {
        return socketNioServerWrite.writeAck(data, channel, seconds);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data) {
        return socketNioServerWrite.writeSync(data, 10, channel);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds) {
        return socketNioServerWrite.writeSync(data, seconds, channel);
    }
}

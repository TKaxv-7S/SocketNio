package server;

import com.tk.socket.SocketJSONDataDto;
import io.netty.channel.Channel;

import java.io.Serializable;

public class SocketServerChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * channel
     */
    private Channel channel;

    private SocketNioServer socketNioServer;

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public SocketServerChannel() {
    }

    public SocketServerChannel(Channel channel, SocketNioServer socketNioServer) {
        this.channel = channel;
        this.socketNioServer = socketNioServer;
    }

    public static SocketServerChannel build(Channel channel, SocketNioServer socketNioServer) {
        return new SocketServerChannel(channel, socketNioServer);
    }

    public void write(SocketJSONDataDto data) {
        socketNioServer.write(data, channel);
    }

    public boolean writeAck(SocketJSONDataDto data) {
        return socketNioServer.writeAck(data, channel);
    }

    public boolean writeAck(SocketJSONDataDto data, int seconds) {
        return socketNioServer.writeAck(data, channel, seconds);
    }

    public SocketJSONDataDto writeSync(SocketJSONDataDto data) {
        return socketNioServer.writeSync(data, 10, channel);
    }

    public SocketJSONDataDto writeSync(SocketJSONDataDto data, int seconds) {
        return socketNioServer.writeSync(data, seconds, channel);
    }

}

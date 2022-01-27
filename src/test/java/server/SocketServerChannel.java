package server;

import cn.hutool.json.JSONObject;
import com.tk.socket.SocketDataDto;
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

    public <T> void write(SocketDataDto<T> data) {
        socketNioServer.write(data, channel);
    }

    public <T> boolean writeAck(SocketDataDto<T> data) {
        return socketNioServer.writeAck(data, channel);
    }

    public <T> boolean writeAck(SocketDataDto<T> data, int seconds) {
        return socketNioServer.writeAck(data, channel, seconds);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data) {
        return socketNioServer.writeSync(data, 10, channel);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data, int seconds) {
        return socketNioServer.writeSync(data, seconds, channel);
    }

}

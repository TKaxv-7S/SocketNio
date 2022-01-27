package client;

import cn.hutool.json.JSONObject;
import com.tk.socket.SocketDataDto;
import io.netty.channel.Channel;

import java.io.Serializable;

public class SocketClientChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    private SocketNioClient SocketNioClient;

    public SocketClientChannel() {
    }

    public SocketClientChannel(SocketNioClient SocketNioClient) {
        this.SocketNioClient = SocketNioClient;
    }

    public static SocketClientChannel build(SocketNioClient SocketNioClient) {
        return new SocketClientChannel(SocketNioClient);
    }

    public <T> void write(SocketDataDto<T> data) {
        SocketNioClient.write(data);
    }

    public <T> boolean writeAck(SocketDataDto<T> data) {
        return SocketNioClient.writeAck(data);
    }

    public <T> boolean writeAck(SocketDataDto<T> data, int seconds) {
        return SocketNioClient.writeAck(data, seconds);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data, Channel channel) {
        return SocketNioClient.writeSync(data, 10);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data, int seconds, Channel channel) {
        return SocketNioClient.writeSync(data, seconds);
    }

}

package client;

import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.client.SocketClientHandler;
import com.tk.socket.client.SocketNioClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class SocketClientDataHandler implements SocketClientHandler {

    @Override
    public SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, SocketNioClient socketNioClient) {
        Map<String, Object> data = msgData.getData(Map.class.getGenericSuperclass());
        //TODO 业务处理，同步请求则返回数据
        data.put("isDone", true);
        return msgData;
    }

}

package client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.client.SocketClientHandler;
import com.tk.socket.client.SocketNioClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class SocketClientDataHandler implements SocketClientHandler {

    private final TypeReference<Map<String, Object>> valueTypeRef = new TypeReference<Map<String, Object>>() {
    };

    @Override
    public SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, SocketNioClient socketNioClient) {
        Map<String, Object> data = msgData.getData(valueTypeRef);
        //TODO 业务处理，同步请求则返回数据
        data.put("isDone", true);
        return msgData;
    }

}

package server;

import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.server.SocketServerHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class SocketServerDataHandler implements SocketServerHandler {

    @Override
    public SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, com.tk.socket.server.SocketServerChannel serverChannel) {
        Map<String, Object> data = msgData.getData(Map.class.getGenericSuperclass());
        //TODO 业务处理，同步请求则返回数据
        data.put("isDone", true);
        return msgData;
    }
}

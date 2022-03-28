package server;

import cn.hutool.json.JSONObject;
import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.server.SocketServerHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocketServerDataHandler implements SocketServerHandler {

    @Override
    public SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, com.tk.socket.server.SocketServerChannel serverChannel) {
        JSONObject data = msgData.getData(JSONObject.class);
        //TODO 业务处理，同步请求则返回数据
        data.set("isDone", true);
        return msgData;
    }
}

package client;

import cn.hutool.json.JSONObject;
import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.client.SocketClientHandler;
import com.tk.socket.client.SocketNioClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocketClientDataHandler implements SocketClientHandler {

    @Override
    public SocketMsgDataDto handle(String method, SocketMsgDataDto msgData, SocketNioClient socketNioClient) {
        JSONObject data = msgData.getData(JSONObject.class);
        //TODO 业务处理，同步请求则返回数据
        data.set("isDone", true);
        return msgData;
    }

}

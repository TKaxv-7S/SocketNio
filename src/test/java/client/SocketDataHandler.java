package client;

import cn.hutool.json.JSONObject;
import com.tk.socket.SocketJSONDataDto;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocketDataHandler {

    public Object handle(SocketJSONDataDto socketJSONDataDto, SocketNioClient socketNioClient) {
        String method = socketJSONDataDto.getMethod();
        JSONObject data = socketJSONDataDto.getData(JSONObject.class);
        //TODO 业务处理，同步请求则返回数据
        data.set("isDone", true);
        return data;
    }

}

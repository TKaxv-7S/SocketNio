package server;

import cn.hutool.json.JSONObject;
import com.tk.socket.SocketDataDto;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SocketDataHandler {

    public Object handle(SocketDataDto<JSONObject> socketDataDto, SocketServerChannel serverChannel) {
        String method = socketDataDto.getMethod();
        JSONObject data = socketDataDto.getData();
        //TODO 业务处理，同步请求则返回数据
        data.set("isDone", true);
        return data;
    }

}

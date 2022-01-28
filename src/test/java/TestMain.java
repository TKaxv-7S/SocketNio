import client.SocketNioClient;
import cn.hutool.json.JSONObject;
import com.tk.socket.SocketDataDto;
import io.netty.util.ResourceLeakDetector;
import lombok.extern.slf4j.Slf4j;
import server.ChannelCache;
import server.SocketNioServer;

import java.nio.charset.StandardCharsets;

@Slf4j
public class TestMain {

    public static void main(String[] args) throws InterruptedException {
        String appKey = "socket-test-client";
        byte[] secretBytes = "zacXa/U2bSHs/iQp".getBytes(StandardCharsets.UTF_8);
        int serverPort = 8089;
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        ChannelCache.setSecret(appKey, secretBytes);

        SocketNioServer socketNioServer = new SocketNioServer(serverPort);
        socketNioServer.initNioServer();

        SocketNioClient socketNioClient = new SocketNioClient("127.0.0.1", serverPort, secretBytes, appKey.getBytes(StandardCharsets.UTF_8));
        socketNioClient.initNioClientAsync();
        Thread.sleep(1000);

        int cycleCount = 200;
        int count = 0;
        while (count < cycleCount) {
            count++;
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.set("test", "socket");
                jsonObject.set("isOk", true);
                SocketDataDto<JSONObject> socketDataDto = SocketDataDto.build("reg", jsonObject);
                log.debug("客户端第 {} 次普通写", count);
                socketNioClient.write(socketDataDto);
                log.debug("客户端第 {} 次普通写完成", count);
                jsonObject.set("isAck", true);
                log.debug("客户端第 {} 次ACK写", count);
                log.debug("客户端第 {} 次ACK写结果：{}", count, socketNioClient.writeAck(socketDataDto));
                jsonObject.set("isSync", true);
                log.debug("客户端第 {} 次同步写", count);
                log.debug("客户端第 {} 次同步写结果：{}", count, socketNioClient.writeSync(socketDataDto));
                /*if (count % 2 == 1) {
                    this.close();
                }*/
            } catch (Exception e) {
                log.error("客户端写入异常", e);
            }
        }
        log.debug("客户端 {} 次循环测试完成", count);
        System.exit(0);
    }
}

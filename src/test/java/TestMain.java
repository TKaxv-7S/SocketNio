import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import client.SocketNioClient;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONObject;
import com.tk.socket.SocketDataDto;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import server.SocketChannelSecretUtil;
import server.SocketNioServer;

import java.nio.charset.StandardCharsets;

@Slf4j
public class TestMain {

    public static void main(String[] args) throws InterruptedException {
        String appKey = "socket-test-client";
        byte[] secretBytes = "zacXa/U2bSHs/iQp".getBytes(StandardCharsets.UTF_8);
        int serverPort = 8089;

//        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        SocketChannelSecretUtil.setSecret(appKey, secretBytes);
        SocketNioServer socketNioServer = new SocketNioServer(serverPort);
        socketNioServer.initNioServerSync();

        SocketNioClient socketNioClient = new SocketNioClient("127.0.0.1", serverPort, secretBytes, appKey.getBytes(StandardCharsets.UTF_8));
        socketNioClient.initNioClientSync();

        Thread.sleep(1000);

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger("root");
//        logger.setLevel(Level.ERROR);
        logger.setLevel(Level.DEBUG);

        ThreadUtil.execute(() -> {
            int cycleCount = 200;
            int count = 0;
            while (count < cycleCount) {
                count++;
                try {
                    JSONObject jsonObject = new JSONObject();
//                jsonObject.set("text", RandomUtil.randomString("你好啊", 900099));
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
                    //Thread.sleep(1000);
                } catch (Exception e) {
                    log.error("客户端写入异常", e);
                }
            }
            log.debug("客户端 {} 次循环测试完成", count);
            socketNioClient.shutdown();
            socketNioServer.shutdown();
            System.exit(0);
        });
    }
}

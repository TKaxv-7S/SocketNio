import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import client.SocketNioClient;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONObject;
import com.tk.socket.SocketClientConfig;
import com.tk.socket.SocketJSONDataDto;
import com.tk.socket.SocketServerConfig;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import server.SocketChannelSecretUtil;
import server.SocketNioServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
public class TestMain {

    public static void main(String[] args) throws InterruptedException {
        String appKey = "socket-test-client";
        byte[] secretBytes = "zacXa/U2bSHs/iQp".getBytes(StandardCharsets.UTF_8);
        SocketChannelSecretUtil.setSecret(appKey, secretBytes);
        int serverPort = 8089;

//        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        SocketServerConfig socketServerConfig = new SocketServerConfig();
        socketServerConfig.setPort(serverPort);
        socketServerConfig.setMsgSizeLimit(null);
        socketServerConfig.setSingleThreadDataConsumerCount(100);
        socketServerConfig.setEventLoopThreadCount(10);
        socketServerConfig.setMaxHandlerDataThreadCount(200);
        socketServerConfig.setSingleThreadDataConsumerCount(100);
        SocketNioServer socketNioServer = new SocketNioServer(socketServerConfig);
        socketNioServer.initNioServerSync();

        SocketClientConfig socketClientConfig = new SocketClientConfig();
        socketClientConfig.setHost("127.0.0.1");
        socketClientConfig.setPort(serverPort);
        socketClientConfig.setMsgSizeLimit(null);
        socketClientConfig.setMaxHandlerDataThreadCount(10);
        socketClientConfig.setSingleThreadDataConsumerCount(100);
        socketClientConfig.setPoolMaxTotal(10);
        socketClientConfig.setPoolMaxIdle(5);
        socketClientConfig.setPoolMinIdle(2);
        socketClientConfig.setPoolMaxWait(Duration.ofMillis(2000));
        SocketNioClient socketNioClient = new SocketNioClient(socketClientConfig, secretBytes, appKey.getBytes(StandardCharsets.UTF_8));
        socketNioClient.initNioClientSync();

        Thread.sleep(1000);

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = loggerContext.getLogger("root");
//        logger.setLevel(Level.ERROR);
        logger.setLevel(Level.DEBUG);

        ThreadUtil.execute(() -> {
            int cycleCount = 200;
            int count = 0;
            long startTime = System.currentTimeMillis();
            while (count < cycleCount) {
                count++;
                try {
                    JSONObject jsonObject = new JSONObject();
//                jsonObject.set("text", RandomUtil.randomString("你好啊", 900099));
                    jsonObject.set("test", "socket");
                    jsonObject.set("isOk", true);
                    SocketJSONDataDto socketJSONDataDto = SocketJSONDataDto.build("reg", jsonObject);
                    log.debug("客户端第 {} 次普通写", count);
                    socketNioClient.write(socketJSONDataDto);
                    log.debug("客户端第 {} 次普通写完成", count);
                    jsonObject.set("isAck", true);
                    log.debug("客户端第 {} 次ACK写", count);
                    log.debug("客户端第 {} 次ACK写结果：{}", count, socketNioClient.writeAck(socketJSONDataDto));
                    jsonObject.set("isSync", true);
                    log.debug("客户端第 {} 次同步写", count);
                    log.debug("客户端第 {} 次同步写结果：{}", count, socketNioClient.writeSync(socketJSONDataDto));
                    /*if (count % 2 == 1) {
                        this.close();
                    }*/
                    //Thread.sleep(1000);
                } catch (Exception e) {
                    log.error("客户端写入异常", e);
                }
            }
            log.debug("客户端 {} 次循环测试完成", count);
            log.debug("耗时：{}毫秒", System.currentTimeMillis() - startTime);
            socketNioClient.shutdown();
            socketNioServer.shutdown();
            System.exit(0);
        });
    }
}

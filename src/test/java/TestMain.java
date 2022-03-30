import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import client.SocketClientDataHandler;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONObject;
import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.client.SocketClientConfig;
import com.tk.socket.client.SocketNioClient;
import com.tk.socket.server.SocketClientCache;
import com.tk.socket.server.SocketNioServer;
import com.tk.socket.server.SocketSecretDto;
import com.tk.socket.server.SocketServerConfig;
import com.tk.utils.Base64SecretUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import server.SocketServerDataHandler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
public class TestMain {

    public static void main(String[] args) throws InterruptedException {
        String appKey = "socket-test-client";
        byte[] secretBytes = "zacXa/U2bSHs/iQp".getBytes(StandardCharsets.UTF_8);
        int serverPort = 8089;

//        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        SocketServerConfig socketServerConfig = new SocketServerConfig();
        socketServerConfig.setPort(serverPort);
        socketServerConfig.setMsgSizeLimit(null);
        socketServerConfig.setEventLoopThreadCount(10);
        socketServerConfig.setMaxHandlerDataThreadCount(4);
        SocketNioServer socketNioServer = new SocketNioServer(socketServerConfig, new SocketServerDataHandler());
        socketNioServer.initNioServerSync();
        SocketClientCache<SocketSecretDto> socketClientCache = (SocketClientCache<SocketSecretDto>) socketNioServer.getSocketClientCache();
        socketClientCache.addSecret(SocketSecretDto.build(
                appKey,
                secretBytes,
                Base64SecretUtil::encodeToByteArray,
                Base64SecretUtil::decodeToByteArray,
                2,
                30,
                35,
                "AES"
        ));

        SocketClientConfig socketClientConfig = new SocketClientConfig();
        socketClientConfig.setHost("127.0.0.1");
        socketClientConfig.setPort(serverPort);
        socketClientConfig.setAppKey(appKey);
        socketClientConfig.setSecret(secretBytes);
        socketClientConfig.setMsgEncode(Base64SecretUtil::encodeToByteArray);
        socketClientConfig.setMsgDecode(Base64SecretUtil::decodeToByteArray);
        socketClientConfig.setMsgSizeLimit(null);
        socketClientConfig.setMaxHandlerDataThreadCount(4);
        socketClientConfig.setPoolMaxTotal(10);
        socketClientConfig.setPoolMaxIdle(5);
        socketClientConfig.setPoolMinIdle(2);
        socketClientConfig.setPoolMaxWait(Duration.ofMillis(2000));
        SocketNioClient socketNioClient = new SocketNioClient(socketClientConfig, new SocketClientDataHandler());
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
                    SocketMsgDataDto socketJSONDataDto = SocketMsgDataDto.build("reg", jsonObject);
                    log.debug("客户端第 {} 次普通写", count);
                    socketNioClient.write(socketJSONDataDto);
                    log.debug("客户端第 {} 次普通写完成", count);
                    jsonObject.set("isAck", true);
                    log.debug("客户端第 {} 次ACK写", count);
                    log.debug("客户端第 {} 次ACK写结果：{}", count, socketNioClient.writeAck(socketJSONDataDto));
                    jsonObject.set("isSync", true);
                    log.debug("客户端第 {} 次同步写", count);
                    log.debug("客户端第 {} 次同步写结果：{}", count, socketNioClient.writeSync(socketJSONDataDto).getData());
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

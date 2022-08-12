import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import client.SocketClientDataHandler;
import com.tk.socket.SocketMsgDataDto;
import com.tk.socket.client.SocketClientConfig;
import com.tk.socket.client.SocketNioClient;
import com.tk.socket.server.SocketClientCache;
import com.tk.socket.server.SocketNioServer;
import com.tk.socket.server.SocketSecretDto;
import com.tk.socket.server.SocketServerConfig;
import com.tk.utils.SecuretUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import server.SocketServerDataHandler;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

@Slf4j
public class TestMain {

    public static void main(String[] args) throws InterruptedException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        String appKey = "socket-test-client";
        byte[] secretBytes = "zacXa/U2bSHs/iQp".getBytes(StandardCharsets.UTF_8);
        Cipher aesEncryptCipher = SecuretUtil.getAESEncryptCipher(secretBytes);
        Cipher aesDecryptCipher = SecuretUtil.getAESDecryptCipher(secretBytes);
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
                (byte[] data, byte[] secret) -> {
                    try {
                        return aesEncryptCipher.doFinal(data);
                    } catch (IllegalBlockSizeException | BadPaddingException e) {
                        throw new RuntimeException(e);
                    }
                },
                (byte[] data, byte[] secret) -> {
                    try {
                        return aesDecryptCipher.doFinal(data);
                    } catch (IllegalBlockSizeException | BadPaddingException e) {
                        throw new RuntimeException(e);
                    }
                },
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
        socketClientConfig.setMsgEncode((byte[] data, byte[] secret) -> {
            try {
                return aesEncryptCipher.doFinal(data);
            } catch (IllegalBlockSizeException | BadPaddingException e) {
                throw new RuntimeException(e);
            }
        });
        socketClientConfig.setMsgDecode((byte[] data, byte[] secret) -> {
            try {
                return aesDecryptCipher.doFinal(data);
            } catch (IllegalBlockSizeException | BadPaddingException e) {
                throw new RuntimeException(e);
            }
        });
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

        Executors.newCachedThreadPool().execute(() -> {
            int cycleCount = 200;
            int count = 0;
            long startTime = System.currentTimeMillis();
            while (count < cycleCount) {
                count++;
                try {
                    Map<String, Object> map = new HashMap<>();
//                jsonObject.set("text", RandomUtil.randomString("你好啊", 900099));
                    map.put("test", "socket");
                    map.put("isOk", true);
                    SocketMsgDataDto socketJSONDataDto = SocketMsgDataDto.build("reg", map);
                    log.debug("客户端第 {} 次普通写", count);
                    socketNioClient.write(socketJSONDataDto);
                    log.debug("客户端第 {} 次普通写完成", count);
                    map.put("isAck", true);
                    log.debug("客户端第 {} 次ACK写", count);
                    log.debug("客户端第 {} 次ACK写结果：{}", count, socketNioClient.writeAck(socketJSONDataDto));
                    map.put("isSync", true);
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
            socketNioClient.shutdownNow();
            socketNioServer.shutdownNow();
            System.exit(0);
        });
    }
}

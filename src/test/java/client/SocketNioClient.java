package client;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import com.tk.socket.*;
import com.tk.utils.Base64SecretUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class SocketNioClient extends AbstractSocketNioClient {

    private final String tcpServerAddress;

    private final Integer tcpServerPort;

    private final byte[] tcpServerSecret;

    private final byte[] tcpAppKey;

    private final TypeReference<SocketJSONDataDto> socketDataDtoTypeReference = new TypeReference<SocketJSONDataDto>() {
    };

    public SocketNioClient(String tcpServerAddress, Integer tcpServerPort, byte[] tcpServerSecret, byte[] tcpAppKey) {
        this.tcpServerAddress = tcpServerAddress;
        this.tcpServerPort = tcpServerPort;
        this.tcpServerSecret = tcpServerSecret;
        this.tcpAppKey = tcpAppKey;
    }

    private final Map<Integer, SocketJSONDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final SocketDataHandler socketDataHandler = new SocketDataHandler();

    public SocketJSONDataDto readSocketDataDto(byte[] data) {
        return JSONUtil.toBean(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference, true);
    }

    public <T> void write(SocketJSONDataDto data) {
        write(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public <T> boolean writeAck(SocketJSONDataDto data) {
        return writeAck(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public <T> boolean writeAck(SocketJSONDataDto data, int seconds) {
        return writeAck(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8), seconds);
    }

    public <T> SocketJSONDataDto writeSync(SocketJSONDataDto data) {
        return writeSync(data, 10);
    }

    public <T> SocketJSONDataDto writeSync(SocketJSONDataDto data, int seconds) {
        if (!getIsInit()) {
            initNioClientSync();
        }
        Integer dataId = data.getClientDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setClientDataId(dataId);
        }
        SocketJSONDataDto syncDataDto = new SocketJSONDataDto(dataId, true);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
                syncDataDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
            SocketJSONDataDto socketJSONDataDto = syncDataMap.remove(dataId);
            String method = socketJSONDataDto.getMethod();
            if (!StringUtils.equals(method, "syncReturn")) {
                throw new SocketException("写入超时");
            }
            return socketJSONDataDto;
        } catch (InterruptedException e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw new SocketException("同步写入异常");
        } catch (Exception e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw e;
        }
    }

    @Override
    public SocketClientConfig setConfig() {
        SocketClientConfig socketClientConfig = new SocketClientConfig();
        socketClientConfig.setHost(tcpServerAddress);
        socketClientConfig.setPort(tcpServerPort);
        socketClientConfig.setMsgSizeLimit(null);
        socketClientConfig.setMaxHandlerDataThreadCount(10);
        socketClientConfig.setSingleThreadDataConsumerCount(100);
        socketClientConfig.setPoolMaxTotal(10);
        socketClientConfig.setPoolMaxIdle(5);
        socketClientConfig.setPoolMinIdle(2);
        socketClientConfig.setPoolMaxWait(Duration.ofMillis(2000));
        return socketClientConfig;
    }

    @Override
    public Consumer<byte[]> setDataConsumer() {
        return bytes -> {
            SocketJSONDataDto socketJSONDataDto = readSocketDataDto(bytes);
            Integer dataId = socketJSONDataDto.getClientDataId();
            if (dataId != null) {
                SocketJSONDataDto syncDataDto = syncDataMap.get(dataId);
                if (syncDataDto != null) {
                    synchronized (syncDataDto) {
                        syncDataDto.setData(socketJSONDataDto.getData());
                        syncDataDto.setMethod("syncReturn");
                        syncDataDto.notify();
                    }
                }
                //不再继续执行，即便method不为空
                return;
            }
            String method = socketJSONDataDto.getMethod();
            if (StringUtils.isNotBlank(method)) {
                Object data = socketDataHandler.handle(socketJSONDataDto, this);
                Integer serverDataId = socketJSONDataDto.getServerDataId();
                if (serverDataId != null) {
                    SocketJSONDataDto syncDataDto;
                    syncDataDto = new SocketJSONDataDto(data);
                    syncDataDto.setServerDataId(serverDataId);
                    write(syncDataDto);
                }
            }
            log.debug("客户端已读");
        };
    }

    @Override
    public SocketEncodeDto encode(byte[] data) {
        byte[] encodeData = Base64SecretUtil.encodeToByteArray(data, tcpServerSecret);
        int dataLength = encodeData.length;
        int appKeyLength = tcpAppKey.length;
        byte[] lengthBytes = SocketMessageUtil.intToByteArray(appKeyLength);
        int index = 4 + appKeyLength;
        byte[] encode = new byte[dataLength + index];
        System.arraycopy(encodeData, 0, encode, index, dataLength);
        System.arraycopy(lengthBytes, 0, encode, 0, 4);
        System.arraycopy(tcpAppKey, 0, encode, 4, appKeyLength);
        return new SocketEncodeDto(encode, (byte) 0xFF);
    }

    @Override
    public byte[] decode(byte[] data, byte secretByte) {
        return Base64SecretUtil.decodeToByteArray(data, tcpServerSecret);
    }

}

package client;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tk.socket.*;
import com.tk.utils.Base64SecretUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
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

    private TypeReference<SocketDataDto<JSONObject>> socketDataDtoTypeReference = new TypeReference<SocketDataDto<JSONObject>>() {
    };

    public SocketNioClient(String tcpServerAddress, Integer tcpServerPort, byte[] tcpServerSecret, byte[] tcpAppKey) {
        this.tcpServerAddress = tcpServerAddress;
        this.tcpServerPort = tcpServerPort;
        this.tcpServerSecret = tcpServerSecret;
        this.tcpAppKey = tcpAppKey;
    }

    private final Map<Integer, SocketDataDto<JSONObject>> syncDataMap = new ConcurrentHashMap<>();

    //TODO 实现
    private final SocketDataHandler socketDataHandler = new SocketDataHandler();

    public SocketDataDto<JSONObject> readSocketDataDto(byte[] data) {
        return JSONUtil.toBean(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference, true);
    }

    public <T> void write(SocketDataDto<T> data) {
        write(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public <T> boolean writeAck(SocketDataDto<T> data) {
        return writeAck(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public <T> boolean writeAck(SocketDataDto<T> data, int seconds) {
        return writeAck(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8), seconds);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data) {
        return writeSync(data, 10);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data, int seconds) {
        if (isClosed()) {
            initNioClientSync();
        }
        Integer dataId = data.getClientDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setClientDataId(dataId);
        }
        SocketDataDto<JSONObject> syncDataDto = new SocketDataDto<>(dataId, true);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
                syncDataDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
            SocketDataDto<JSONObject> socketDataDto = syncDataMap.remove(dataId);
            String method = socketDataDto.getMethod();
            if (!StringUtils.equals(method, "syncReturn")) {
                throw new SocketException("写入超时");
            }
            return socketDataDto;
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
    public String setHost() {
        return tcpServerAddress;
    }

    @Override
    public Integer setPort() {
        return tcpServerPort;
    }

    @Override
    public Consumer<byte[]> setDataConsumer() {
        return bytes -> {
            SocketDataDto<JSONObject> socketDataDto = readSocketDataDto(bytes);
            Integer dataId = socketDataDto.getClientDataId();
            if (dataId != null) {
                SocketDataDto<JSONObject> syncDataDto = syncDataMap.get(dataId);
                if (syncDataDto != null) {
                    synchronized (syncDataDto) {
                        syncDataDto.setData(socketDataDto.getData());
                        syncDataDto.setMethod("syncReturn");
                        syncDataDto.notify();
                    }
                }
                //不再继续执行，即便method不为空
                return;
            }
            String method = socketDataDto.getMethod();
            if (StringUtils.isNotBlank(method)) {
                Object data = socketDataHandler.handle(socketDataDto, this);
                Integer serverDataId = socketDataDto.getServerDataId();
                if (serverDataId != null) {
                    SocketDataDto<JSONObject> syncDataDto;
                    syncDataDto = new SocketDataDto<>(JSONUtil.parseObj(data));
                    syncDataDto.setServerDataId(serverDataId);
                    write(syncDataDto);
                }
            }
            log.debug("客户端已读");
        };
    }

    @Override
    public Integer setMsgSizeLimit() {
        return null;
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

    @Override
    public int setMaxHandlerDataThreadCount() {
        return 10;
    }

    @Override
    public int setSingleThreadDataConsumerCount() {
        return 100;
    }

}

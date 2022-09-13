package com.tk.socket.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tk.socket.*;
import com.tk.socket.entity.SocketSecret;
import com.tk.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class SocketNioClient extends AbstractSocketNioClient {

    private final SocketClientHandler socketClientHandler;

    private final byte[] appKey;

    private final SocketSecret secret;

    private final Heartbeat heartbeat;

    private final byte[] heartbeatBytes = JsonUtil.toJsonString(SocketMsgDataDto.build("", null)).getBytes(StandardCharsets.UTF_8);

    public void setHeartbeatInterval(Integer heartbeatInterval) {
        heartbeat.setHeartbeatInterval(heartbeatInterval);
    }

    public Integer getHeartbeatInterval() {
        return heartbeat.getHeartbeatInterval();
    }

    class Heartbeat extends Thread {

        private Long heartbeatInterval;

        public void setHeartbeatInterval(Integer heartbeatInterval) {
            Integer oldHeartbeatInterval = this.getHeartbeatInterval();
            this.heartbeatInterval = Math.max(Objects.isNull(heartbeatInterval) ? 30000L : heartbeatInterval * 1000L, 15000L);
            log.info("socketNioClient心跳间隔时间已更新，旧：{}秒，新：{}秒", oldHeartbeatInterval, heartbeatInterval);
        }

        public Integer getHeartbeatInterval() {
            return ((Long) (heartbeatInterval / 1000)).intValue();
        }

        public Heartbeat(Integer heartbeatInterval) {
            this.heartbeatInterval = Math.max(Objects.isNull(heartbeatInterval) ? 30000L : heartbeatInterval * 1000L, 15000L);
        }

        @Override
        public void run() {
            Thread thread = Thread.currentThread();
            while (!thread.isInterrupted()) {
                try {
                    write(heartbeatBytes);
                } catch (Exception e) {
                    log.warn("SocketNioClient心跳异常", e);
                }
                try {
                    Thread.sleep(heartbeatInterval);
                } catch (InterruptedException e) {
                    log.warn("SocketNioClient心跳线程已关闭");
                    return;
                }
            }
            log.warn("SocketNioClient心跳线程已关闭");
        }
    }

    public SocketNioClient(SocketClientConfig config, SocketClientHandler socketClientHandler) {
        super(config);
        this.appKey = config.getAppKey().getBytes(StandardCharsets.UTF_8);
        this.secret = config.getSecret();
        this.socketClientHandler = socketClientHandler;
        this.heartbeat = new Heartbeat(null);
        super.connCallback = (client) -> heartbeat.start();
    }

    private final Map<Integer, SocketMsgDataDto> syncDataMap = new ConcurrentHashMap<>();

    private final TypeReference<SocketMsgDataDto> socketDataDtoTypeReference = new TypeReference<SocketMsgDataDto>() {
    };

    public SocketMsgDataDto readSocketDataDto(byte[] data) {
        return JsonUtil.parseObject(new String(data, StandardCharsets.UTF_8), socketDataDtoTypeReference);
    }

    public void write(SocketJSONDataDto data) {
        write(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeAck(SocketJSONDataDto data) {
        return writeAck(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8));
    }

    public boolean writeAck(SocketJSONDataDto data, int seconds) {
        return writeAck(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8), seconds);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data) {
        return writeSync(data, 10);
    }

    public SocketMsgDataDto writeSync(SocketMsgDataDto data, int seconds) {
        if (!getIsInit()) {
            initNioClientSync();
        }
        Integer dataId = data.getClientDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setClientDataId(dataId);
        }
        SocketMsgDataDto syncDataDto = new SocketMsgDataDto(dataId, true);
        syncDataMap.put(dataId, syncDataDto);
        try {
            synchronized (syncDataDto) {
                write(JsonUtil.toJsonString(data).getBytes(StandardCharsets.UTF_8));
                syncDataDto.wait(TimeUnit.SECONDS.toMillis(seconds));
            }
            SocketMsgDataDto socketDataDto = syncDataMap.remove(dataId);
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
    public Consumer<byte[]> setDataConsumer() {
        return bytes -> {
            SocketMsgDataDto socketDataDto = readSocketDataDto(bytes);
            Integer dataId = socketDataDto.getClientDataId();
            if (dataId != null) {
                SocketMsgDataDto syncDataDto = syncDataMap.get(dataId);
                if (syncDataDto != null) {
                    synchronized (syncDataDto) {
                        syncDataDto.setData(socketDataDto.getData());
                        syncDataDto.setCode(socketDataDto.getCode());
                        syncDataDto.setMsg(socketDataDto.getMsg());
                        syncDataDto.setMethod("syncReturn");
                        syncDataDto.notify();
                    }
                }
                //不再继续执行，即便method不为空
                return;
            }
            String method = socketDataDto.getMethod();
            if (StringUtils.isNotBlank(method)) {
                if (socketDataDto.isSuccess()) {
                    Integer serverDataId = socketDataDto.getServerDataId();
                    SocketMsgDataDto syncDataDto;
                    try {
                        log.debug("服务端执行method：{}", method);
                        syncDataDto = socketClientHandler.handle(method, socketDataDto, this);
                    } catch (Exception e) {
                        syncDataDto = SocketMsgDataDto.buildError(e.getMessage());
                    }
                    if (serverDataId != null) {
                        syncDataDto.setServerDataId(serverDataId);
                        write(syncDataDto);
                    }
                } else {
                    log.error("服务端处理失败：{}", JsonUtil.toJsonString(socketDataDto));
                }
            }
            log.debug("客户端已读");
        };
    }

    @Override
    public SocketWrapMsgDto encode(byte[] data) {
        byte[] encodeData = secret.encode(data);
        int dataLength = encodeData.length;
        int appKeyLength = appKey.length;
        byte[] lengthBytes = SocketMessageUtil.intToByteArray(appKeyLength);
        int index = 4 + appKeyLength;
        byte[] encode = new byte[dataLength + index];
        System.arraycopy(encodeData, 0, encode, index, dataLength);
        System.arraycopy(lengthBytes, 0, encode, 0, 4);
        System.arraycopy(appKey, 0, encode, 4, appKeyLength);
        return new SocketWrapMsgDto(encode, (byte) 0xFF);
    }

    @Override
    public byte[] decode(byte[] data, byte secretByte) {
        return secret.decode(data);
    }

    @Override
    public void shutdownNow() {
        super.shutdownNow();
        heartbeat.interrupt();
    }
}

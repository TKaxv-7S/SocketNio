package server;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tk.socket.*;
import com.tk.utils.Base64SecretUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
public class SocketNioServer extends AbstractSocketNioServer {

    private final Integer serverPort;

    private final Map<Integer, SocketDataDto<JSONObject>> syncDataMap = new ConcurrentHashMap<>();

    private final SocketDataHandler socketDataHandler = new SocketDataHandler();

    public SocketNioServer(Integer serverPort) {
        this.serverPort = serverPort;
    }

    public <T> void write(SocketDataDto<T> data, Channel channel) {
        write(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public <T> boolean writeAck(SocketDataDto<T> data, Channel channel) {
        return writeAck(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
    }

    public <T> boolean writeAck(SocketDataDto<T> data, Channel channel, int seconds) {
        return writeAck(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8), seconds);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data, Channel channel) {
        return writeSync(data, 10, channel);
    }

    public <T> SocketDataDto<JSONObject> writeSync(SocketDataDto<T> data, int seconds, Channel channel) {
        Integer dataId = data.getServerDataId();
        if (dataId == null) {
            dataId = ThreadLocalRandom.current().nextInt();
            data.setServerDataId(dataId);
        }
        SocketDataDto<JSONObject> syncDataDto = new SocketDataDto<>(dataId, false);
        syncDataMap.put(dataId, syncDataDto);
        try {
            write(channel, JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8));
            synchronized (syncDataDto) {
                syncDataDto.wait(Math.min(TimeUnit.SECONDS.toMillis(seconds), 10000));
            }
            SocketDataDto<JSONObject> socketDataDto = syncDataMap.remove(dataId);
            String method = socketDataDto.getMethod();
            if (!StringUtils.equals(method, "syncReturn")) {
                throw new BusinessException("写入超时");
            }
            return socketDataDto;
        } catch (InterruptedException e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw new BusinessException("同步写入异常");
        } catch (Exception e) {
            log.error("同步写入异常", e);
            syncDataMap.remove(dataId);
            throw e;
        }
    }

    @Override
    public Integer setPort() {
        return serverPort;
    }

    @Override
    public BiConsumer<ChannelHandlerContext, byte[]> setDataConsumer() {
        return (ctx, bytes) -> {
            SocketDataDto<JSONObject> socketDataDto = readSocketDataDto(bytes);
            Integer serverDataId = socketDataDto.getServerDataId();
            if (serverDataId != null) {
                SocketDataDto<JSONObject> syncDataDto = syncDataMap.get(serverDataId);
                if (syncDataDto != null) {
                    syncDataDto.setData(socketDataDto.getData());
                    syncDataDto.setMethod("syncReturn");
                    synchronized (syncDataDto) {
                        syncDataDto.notify();
                    }
                }
                //不再继续执行，即便method不为空
                return;
            }
            String method = socketDataDto.getMethod();
            if (StringUtils.isNotBlank(method)) {
                Channel channel = ctx.channel();
                Object data = socketDataHandler.handle(socketDataDto, SocketServerChannel.build(channel, this));
                Integer clientDataId = socketDataDto.getClientDataId();
                if (data != null && clientDataId != null) {
                    SocketDataDto<JSONObject> syncDataDto;
                    syncDataDto = new SocketDataDto<>(JSONUtil.parseObj(data));
                    syncDataDto.setClientDataId(clientDataId);
                    this.write(syncDataDto, channel);
                }
            }
        };
    }

    @Override
    public Integer setBytesSizeLimit() {
        return null;
    }

    public SocketDataDto<JSONObject> readSocketDataDto(byte[] data) {
        return JSONUtil.toBean(new String(data, StandardCharsets.UTF_8), new TypeReference<SocketDataDto<JSONObject>>() {
        }, true);
    }

    @Override
    public SocketEncodeDto encode(ChannelId channelId, byte[] data) {
        String appKey = ChannelCache.getAppKey(channelId);
        if (appKey != null) {
            byte[] serverSecret = ChannelCache.getSecret(appKey);
            if (serverSecret != null) {
                return new SocketEncodeDto(Base64SecretUtil.encodeToByteArray(data, serverSecret), (byte) 0xFF);
            }
        }
        return null;
    }

    @Override
    public byte[] decode(ChannelId channelId, byte[] data, byte secretByte) {
        int appKeyLength = SocketMessageUtil.byteArrayToInt(data);
        String appKey = ChannelCache.getAppKey(channelId);
        if (appKey == null) {
            byte[] appKeyBytes = new byte[appKeyLength];
            System.arraycopy(data, 4, appKeyBytes, 0, appKeyLength);
            appKey = new String(appKeyBytes, StandardCharsets.UTF_8);
            byte[] serverSecret = ChannelCache.getSecret(appKey);
            if (serverSecret != null) {
                int index = 4 + appKeyLength;
                int length = data.length - index;
                byte[] decode = new byte[length];
                System.arraycopy(data, index, decode, 0, length);
                byte[] bytes = Base64SecretUtil.decodeToByteArray(decode, serverSecret);
                ChannelCache.setAppKey(channelId, appKey);
                return bytes;
            }
        } else {
            byte[] serverSecret = ChannelCache.getSecret(appKey);
            if (serverSecret != null) {
                int index = 4 + appKeyLength;
                int length = data.length - index;
                byte[] decode = new byte[length];
                System.arraycopy(data, index, decode, 0, length);
                return Base64SecretUtil.decodeToByteArray(decode, serverSecret);
            }
        }
        return null;
    }

    @Override
    public int setEventLoopThreadCount() {
        return 10;
    }

    @Override
    public int setMaxHandlerDataThreadCount() {
        return 200;
    }

    @Override
    public int setSingleThreadDataConsumerCount() {
        return 100;
    }

}
